// Vulkan measurement path for Kinetik.
//
// Two real GPU workloads:
//   1. Compute: 16 MiB SSBO, 96 rounds of dependent integer ALU per element.
//   2. Scene:   1024x1024 offscreen render, 64 sin/cos iterations per fragment,
//               24 frames per sample.
//
// Everything is guarded: if the driver refuses any step we return a reason and
// the caller falls back to the OpenGL ES path instead of showing a fake number.
#include <jni.h>
#include <vulkan/vulkan.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <cstdio>
#include <string>
#include <vector>

#include "vk_compute_spv.h"
#include "vk_scene_vert_spv.h"
#include "vk_scene_frag_spv.h"

#define LOG_TAG "dsvk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint32_t kElements = 4u * 1024u * 1024u;   // 16 MiB of uint32
constexpr uint32_t kRounds = 96u;
constexpr uint32_t kGroups = kElements / 256u;
constexpr uint32_t kSceneSize = 1024u;
constexpr uint32_t kSceneFrames = 24u;

struct Gpu {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice phys = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t family = 0;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memProps{};
    std::string name;
    std::string api;
    std::string error;
    bool ok = false;
};

struct Buffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void *mapped = nullptr;
    VkDeviceSize size = 0;
};

uint32_t findMemoryType(const Gpu &g, uint32_t typeBits, VkMemoryPropertyFlags props) {
    for (uint32_t i = 0; i < g.memProps.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) &&
            (g.memProps.memoryTypes[i].propertyFlags & props) == props) {
            return i;
        }
    }
    return UINT32_MAX;
}

bool createBuffer(Gpu &g, VkDeviceSize size, VkBufferUsageFlags usage,
                  VkMemoryPropertyFlags props, Buffer &out) {
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = size;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(g.device, &info, nullptr, &out.buffer) != VK_SUCCESS) {
        g.error = "vkCreateBuffer failed";
        return false;
    }
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(g.device, out.buffer, &req);
    uint32_t type = findMemoryType(g, req.memoryTypeBits, props);
    if (type == UINT32_MAX) {
        g.error = "no suitable memory type for buffer";
        return false;
    }
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = type;
    if (vkAllocateMemory(g.device, &alloc, nullptr, &out.memory) != VK_SUCCESS) {
        g.error = "vkAllocateMemory failed";
        return false;
    }
    vkBindBufferMemory(g.device, out.buffer, out.memory, 0);
    out.size = size;
    if (props & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) {
        vkMapMemory(g.device, out.memory, 0, VK_WHOLE_SIZE, 0, &out.mapped);
    }
    return true;
}

void destroyBuffer(Gpu &g, Buffer &b) {
    if (b.buffer) vkDestroyBuffer(g.device, b.buffer, nullptr);
    if (b.memory) vkFreeMemory(g.device, b.memory, nullptr);
    b = {};
}

VkShaderModule makeShader(Gpu &g, const unsigned char *data, unsigned int size) {
    VkShaderModuleCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    info.codeSize = size;
    // SPIR-V is a uint32 array; the embedded byte array is not guaranteed to be
    // 4-byte aligned as a pointer, so copy it into a properly aligned vector.
    std::vector<uint32_t> code((size + 3) / 4, 0);
    memcpy(code.data(), data, size);
    info.pCode = code.data();
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(g.device, &info, nullptr, &module) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }
    return module;
}

bool submitAndWait(Gpu &g, VkCommandBuffer cmd) {
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;

    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(g.device, &fenceInfo, nullptr, &fence) != VK_SUCCESS) {
        g.error = "vkCreateFence failed";
        return false;
    }
    VkResult r = vkQueueSubmit(g.queue, 1, &submit, fence);
    if (r != VK_SUCCESS) {
        vkDestroyFence(g.device, fence, nullptr);
        g.error = "vkQueueSubmit failed (" + std::to_string((int) r) + ")";
        return false;
    }
    r = vkWaitForFences(g.device, 1, &fence, VK_TRUE, 30ull * 1000 * 1000 * 1000);
    vkDestroyFence(g.device, fence, nullptr);
    if (r != VK_SUCCESS) {
        g.error = "GPU did not finish within 30 s";
        return false;
    }
    return true;
}

bool initGpu(Gpu &g) {
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "DeviceScope";
    app.applicationVersion = 1;
    app.pEngineName = "dsbench";
    app.engineVersion = 1;
    app.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo inst{};
    inst.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    inst.pApplicationInfo = &app;
    if (vkCreateInstance(&inst, nullptr, &g.instance) != VK_SUCCESS) {
        g.error = "vkCreateInstance failed";
        return false;
    }

    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(g.instance, &count, nullptr) != VK_SUCCESS || count == 0) {
        g.error = "Vulkan sürücüsü/aygıtı yok";
        return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(g.instance, &count, devices.data());

    for (VkPhysicalDevice dev : devices) {
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(dev, &props);

        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(qCount);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, families.data());

        int chosen = -1;
        for (uint32_t i = 0; i < qCount; i++) {
            if (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                chosen = (int) i;
                if (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) break;
            }
        }
        if (chosen < 0) continue;

        float prio = 1.0f;
        VkDeviceQueueCreateInfo qinfo{};
        qinfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        qinfo.queueFamilyIndex = (uint32_t) chosen;
        qinfo.queueCount = 1;
        qinfo.pQueuePriorities = &prio;

        VkDeviceCreateInfo dinfo{};
        dinfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        dinfo.queueCreateInfoCount = 1;
        dinfo.pQueueCreateInfos = &qinfo;

        VkDevice device = VK_NULL_HANDLE;
        if (vkCreateDevice(dev, &dinfo, nullptr, &device) != VK_SUCCESS) continue;

        g.phys = dev;
        g.device = device;
        g.family = (uint32_t) chosen;
        vkGetDeviceQueue(device, g.family, 0, &g.queue);
        vkGetPhysicalDeviceMemoryProperties(dev, &g.memProps);

        g.name = props.deviceName;
        char api[64];
        snprintf(api, sizeof(api), "%u.%u.%u",
                 VK_VERSION_MAJOR(props.apiVersion),
                 VK_VERSION_MINOR(props.apiVersion),
                 VK_VERSION_PATCH(props.apiVersion));
        g.api = api;

        VkCommandPoolCreateInfo pinfo{};
        pinfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        pinfo.queueFamilyIndex = g.family;
        pinfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        if (vkCreateCommandPool(device, &pinfo, nullptr, &g.pool) != VK_SUCCESS) {
            g.error = "vkCreateCommandPool failed";
            return false;
        }
        return true;
    }

    g.error = "compute destekli Vulkan aygıtı bulunamadı";
    return false;
}

VkCommandBuffer beginOnce(Gpu &g) {
    VkCommandBufferAllocateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    info.commandPool = g.pool;
    info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    info.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (vkAllocateCommandBuffers(g.device, &info, &cmd) != VK_SUCCESS) {
        g.error = "vkAllocateCommandBuffers failed";
        return VK_NULL_HANDLE;
    }
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &begin);
    return cmd;
}

double measureCompute(Gpu &g) {
    Buffer device{};
    Buffer staging{};
    if (!createBuffer(g, kElements * 4ULL,
                      VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                      VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, device)) return 0.0;
    if (!createBuffer(g, kElements * 4ULL,
                      VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                      staging)) {
        destroyBuffer(g, device);
        return 0.0;
    }
    if (!staging.mapped) {
        g.error = "staging buffer could not be mapped";
        destroyBuffer(g, device);
        destroyBuffer(g, staging);
        return 0.0;
    }
    uint32_t *words = (uint32_t *) staging.mapped;
    for (uint32_t i = 0; i < kElements; i++) words[i] = i * 2654435761u;

    VkCommandBuffer cmd = beginOnce(g);
    if (!cmd) {
        destroyBuffer(g, device);
        destroyBuffer(g, staging);
        return 0.0;
    }
    VkBufferCopy copy{};
    copy.size = kElements * 4ULL;
    vkCmdCopyBuffer(cmd, staging.buffer, device.buffer, 1, &copy);
    vkEndCommandBuffer(cmd);
    bool uploaded = submitAndWait(g, cmd);
    vkFreeCommandBuffers(g.device, g.pool, 1, &cmd);
    if (!uploaded) {
        destroyBuffer(g, device);
        destroyBuffer(g, staging);
        return 0.0;
    }

    VkDescriptorSetLayoutBinding binding{};
    binding.binding = 0;
    binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    binding.descriptorCount = 1;
    binding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 1;
    layoutInfo.pBindings = &binding;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    if (vkCreateDescriptorSetLayout(g.device, &layoutInfo, nullptr, &setLayout) != VK_SUCCESS) {
        g.error = "vkCreateDescriptorSetLayout failed";
        destroyBuffer(g, device);
        destroyBuffer(g, staging);
        return 0.0;
    }

    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.offset = 0;
    push.size = sizeof(uint32_t);

    VkPipelineLayoutCreateInfo plInfo{};
    plInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plInfo.setLayoutCount = 1;
    plInfo.pSetLayouts = &setLayout;
    plInfo.pushConstantRangeCount = 1;
    plInfo.pPushConstantRanges = &push;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    if (vkCreatePipelineLayout(g.device, &plInfo, nullptr, &pipelineLayout) != VK_SUCCESS) {
        g.error = "vkCreatePipelineLayout failed";
        vkDestroyDescriptorSetLayout(g.device, setLayout, nullptr);
        destroyBuffer(g, device);
        destroyBuffer(g, staging);
        return 0.0;
    }

    VkShaderModule module = makeShader(g, vk_compute_spv, vk_compute_spv_size);
    if (!module) {
        g.error = "compute shader reddedildi";
        vkDestroyPipelineLayout(g.device, pipelineLayout, nullptr);
        vkDestroyDescriptorSetLayout(g.device, setLayout, nullptr);
        destroyBuffer(g, device);
        destroyBuffer(g, staging);
        return 0.0;
    }

    VkComputePipelineCreateInfo cp{};
    cp.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cp.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cp.stage.module = module;
    cp.stage.pName = "main";
    cp.layout = pipelineLayout;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkResult pr = vkCreateComputePipelines(g.device, VK_NULL_HANDLE, 1, &cp, nullptr, &pipeline);
    vkDestroyShaderModule(g.device, module, nullptr);
    if (pr != VK_SUCCESS || pipeline == VK_NULL_HANDLE) {
        g.error = "vkCreateComputePipelines failed";
        vkDestroyPipelineLayout(g.device, pipelineLayout, nullptr);
        vkDestroyDescriptorSetLayout(g.device, setLayout, nullptr);
        destroyBuffer(g, device);
        destroyBuffer(g, staging);
        return 0.0;
    }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 1;
    VkDescriptorPoolCreateInfo dpInfo{};
    dpInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpInfo.maxSets = 1;
    dpInfo.poolSizeCount = 1;
    dpInfo.pPoolSizes = &poolSize;
    VkDescriptorPool descPool = VK_NULL_HANDLE;
    vkCreateDescriptorPool(g.device, &dpInfo, nullptr, &descPool);

    VkDescriptorSetAllocateInfo dsInfo{};
    dsInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsInfo.descriptorPool = descPool;
    dsInfo.descriptorSetCount = 1;
    dsInfo.pSetLayouts = &setLayout;
    VkDescriptorSet descSet = VK_NULL_HANDLE;
    if (vkAllocateDescriptorSets(g.device, &dsInfo, &descSet) != VK_SUCCESS) {
        g.error = "vkAllocateDescriptorSets failed";
        vkDestroyDescriptorPool(g.device, descPool, nullptr);
        vkDestroyPipeline(g.device, pipeline, nullptr);
        vkDestroyPipelineLayout(g.device, pipelineLayout, nullptr);
        vkDestroyDescriptorSetLayout(g.device, setLayout, nullptr);
        destroyBuffer(g, device);
        destroyBuffer(g, staging);
        return 0.0;
    }

    VkDescriptorBufferInfo bufInfo{};
    bufInfo.buffer = device.buffer;
    bufInfo.offset = 0;
    bufInfo.range = VK_WHOLE_SIZE;
    VkWriteDescriptorSet write{};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = descSet;
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    write.pBufferInfo = &bufInfo;
    vkUpdateDescriptorSets(g.device, 1, &write, 0, nullptr);

    std::vector<double> samples;
    samples.reserve(4);

    for (int run = 0; run < 4; run++) {          // run 0 is discarded warm-up
        VkCommandBuffer c = beginOnce(g);
        if (!c) break;
        vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(c, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                                0, 1, &descSet, 0, nullptr);
        vkCmdPushConstants(c, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                           sizeof(uint32_t), &kRounds);
        vkCmdDispatch(c, kGroups, 1, 1);
        vkEndCommandBuffer(c);

        auto t0 = std::chrono::steady_clock::now();
        bool fine = submitAndWait(g, c);
        auto t1 = std::chrono::steady_clock::now();
        vkFreeCommandBuffers(g.device, g.pool, 1, &c);
        if (!fine) break;

        double seconds = std::chrono::duration<double>(t1 - t0).count();
        if (run > 0 && seconds > 0) {
            // ~6 integer operations per loop iteration.
            double ops = (double) kElements * (double) kRounds * 6.0;
            samples.push_back(ops / seconds / 1e9);
        }
    }

    vkDestroyDescriptorPool(g.device, descPool, nullptr);
    vkDestroyPipeline(g.device, pipeline, nullptr);
    vkDestroyPipelineLayout(g.device, pipelineLayout, nullptr);
    vkDestroyDescriptorSetLayout(g.device, setLayout, nullptr);
    destroyBuffer(g, device);
    destroyBuffer(g, staging);

    if (samples.empty()) return 0.0;
    std::sort(samples.begin(), samples.end());
    return samples[samples.size() / 2];
}

double measureScene(Gpu &g) {
    // ---- image ------------------------------------------------------------
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {kSceneSize, kSceneSize, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkImage image = VK_NULL_HANDLE;
    if (vkCreateImage(g.device, &imageInfo, nullptr, &image) != VK_SUCCESS) {
        g.error = "vkCreateImage failed";
        return 0.0;
    }
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(g.device, image, &req);
    uint32_t type = findMemoryType(g, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) type = findMemoryType(g, req.memoryTypeBits, 0);
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = type;
    VkDeviceMemory imageMem = VK_NULL_HANDLE;
    if (vkAllocateMemory(g.device, &alloc, nullptr, &imageMem) != VK_SUCCESS) {
        vkDestroyImage(g.device, image, nullptr);
        g.error = "image memory allocation failed";
        return 0.0;
    }
    vkBindImageMemory(g.device, image, imageMem, 0);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageView view = VK_NULL_HANDLE;
    vkCreateImageView(g.device, &viewInfo, nullptr, &view);

    // ---- render pass ------------------------------------------------------
    VkAttachmentDescription color{};
    color.format = VK_FORMAT_R8G8B8A8_UNORM;
    color.samples = VK_SAMPLE_COUNT_1_BIT;
    color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    color.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

    VkAttachmentReference colorRef{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorRef;

    VkSubpassDependency dep{};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo rpInfo{};
    rpInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    rpInfo.attachmentCount = 1;
    rpInfo.pAttachments = &color;
    rpInfo.subpassCount = 1;
    rpInfo.pSubpasses = &subpass;
    rpInfo.dependencyCount = 1;
    rpInfo.pDependencies = &dep;
    VkRenderPass renderPass = VK_NULL_HANDLE;
    if (vkCreateRenderPass(g.device, &rpInfo, nullptr, &renderPass) != VK_SUCCESS) {
        g.error = "vkCreateRenderPass failed";
        vkDestroyImageView(g.device, view, nullptr);
        vkDestroyImage(g.device, image, nullptr);
        vkFreeMemory(g.device, imageMem, nullptr);
        return 0.0;
    }

    VkFramebufferCreateInfo fbInfo{};
    fbInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fbInfo.renderPass = renderPass;
    fbInfo.attachmentCount = 1;
    fbInfo.pAttachments = &view;
    fbInfo.width = kSceneSize;
    fbInfo.height = kSceneSize;
    fbInfo.layers = 1;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    vkCreateFramebuffer(g.device, &fbInfo, nullptr, &framebuffer);

    // ---- pipeline ---------------------------------------------------------
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    push.offset = 0;
    push.size = sizeof(float);

    VkPipelineLayoutCreateInfo plInfo{};
    plInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plInfo.pushConstantRangeCount = 1;
    plInfo.pPushConstantRanges = &push;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    vkCreatePipelineLayout(g.device, &plInfo, nullptr, &layout);

    VkShaderModule vs = makeShader(g, vk_scene_vert_spv, vk_scene_vert_spv_size);
    VkShaderModule fs = makeShader(g, vk_scene_frag_spv, vk_scene_frag_spv_size);
    if (!vs || !fs) {
        g.error = "sahne gölgelendiricisi reddedildi";
        if (vs) vkDestroyShaderModule(g.device, vs, nullptr);
        if (fs) vkDestroyShaderModule(g.device, fs, nullptr);
        vkDestroyPipelineLayout(g.device, layout, nullptr);
        vkDestroyFramebuffer(g.device, framebuffer, nullptr);
        vkDestroyRenderPass(g.device, renderPass, nullptr);
        vkDestroyImageView(g.device, view, nullptr);
        vkDestroyImage(g.device, image, nullptr);
        vkFreeMemory(g.device, imageMem, nullptr);
        return 0.0;
    }

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vs;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fs;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

    VkPipelineInputAssemblyStateCreateInfo ia{};
    ia.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkViewport viewport{0, 0, (float) kSceneSize, (float) kSceneSize, 0.0f, 1.0f};
    VkRect2D scissor{{0, 0}, {kSceneSize, kSceneSize}};
    VkPipelineViewportStateCreateInfo vp{};
    vp.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    vp.viewportCount = 1;
    vp.pViewports = &viewport;
    vp.scissorCount = 1;
    vp.pScissors = &scissor;

    VkPipelineRasterizationStateCreateInfo raster{};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo ms{};
    ms.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState blendAttachment{};
    blendAttachment.colorWriteMask = 0xF;
    VkPipelineColorBlendStateCreateInfo blend{};
    blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blend.attachmentCount = 1;
    blend.pAttachments = &blendAttachment;

    VkGraphicsPipelineCreateInfo gp{};
    gp.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    gp.stageCount = 2;
    gp.pStages = stages;
    gp.pVertexInputState = &vi;
    gp.pInputAssemblyState = &ia;
    gp.pViewportState = &vp;
    gp.pRasterizationState = &raster;
    gp.pMultisampleState = &ms;
    gp.pColorBlendState = &blend;
    gp.layout = layout;
    gp.renderPass = renderPass;
    gp.subpass = 0;

    VkPipeline pipeline = VK_NULL_HANDLE;
    VkResult pr = vkCreateGraphicsPipelines(g.device, VK_NULL_HANDLE, 1, &gp, nullptr, &pipeline);
    vkDestroyShaderModule(g.device, vs, nullptr);
    vkDestroyShaderModule(g.device, fs, nullptr);

    std::vector<double> samples;
    if (pr == VK_SUCCESS && pipeline != VK_NULL_HANDLE) {
        for (int run = 0; run < 4; run++) {      // run 0 discarded
            VkCommandBuffer c = beginOnce(g);
            if (!c) break;
            VkClearValue clear{};
            clear.color = {{0.05f, 0.06f, 0.08f, 1.0f}};
            VkRenderPassBeginInfo rbi{};
            rbi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
            rbi.renderPass = renderPass;
            rbi.framebuffer = framebuffer;
            rbi.renderArea = {{0, 0}, {kSceneSize, kSceneSize}};
            rbi.clearValueCount = 1;
            rbi.pClearValues = &clear;

            vkCmdBeginRenderPass(c, &rbi, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            for (uint32_t f = 0; f < kSceneFrames; f++) {
                float t = (float) f;
                vkCmdPushConstants(c, layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(float), &t);
                vkCmdDraw(c, 3, 1, 0, 0);
            }
            vkCmdEndRenderPass(c);
            vkEndCommandBuffer(c);

            auto t0 = std::chrono::steady_clock::now();
            bool fine = submitAndWait(g, c);
            auto t1 = std::chrono::steady_clock::now();
            vkFreeCommandBuffers(g.device, g.pool, 1, &c);
            if (!fine) break;

            double seconds = std::chrono::duration<double>(t1 - t0).count();
            if (run > 0 && seconds > 0) samples.push_back(kSceneFrames / seconds);
        }
    } else {
        g.error = "vkCreateGraphicsPipelines failed";
    }

    if (pipeline) vkDestroyPipeline(g.device, pipeline, nullptr);
    vkDestroyPipelineLayout(g.device, layout, nullptr);
    vkDestroyFramebuffer(g.device, framebuffer, nullptr);
    vkDestroyRenderPass(g.device, renderPass, nullptr);
    vkDestroyImageView(g.device, view, nullptr);
    vkDestroyImage(g.device, image, nullptr);
    vkFreeMemory(g.device, imageMem, nullptr);

    if (samples.empty()) return 0.0;
    std::sort(samples.begin(), samples.end());
    return samples[samples.size() / 2];
}

void shutdown(Gpu &g) {
    if (g.device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(g.device);
        if (g.pool) vkDestroyCommandPool(g.device, g.pool, nullptr);
        vkDestroyDevice(g.device, nullptr);
    }
    if (g.instance != VK_NULL_HANDLE) vkDestroyInstance(g.instance, nullptr);
    g.device = VK_NULL_HANDLE;
    g.instance = VK_NULL_HANDLE;
}

jstring jstr(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(s.c_str());
}

std::string fmt(double v, int decimals) {
    char buf[64];
    snprintf(buf, sizeof(buf), "%.*f", decimals, v);
    return buf;
}

} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_alican_devicescope_bench_VulkanBench_nRun(JNIEnv *env, jclass) {
    Gpu g;
    double compute = 0.0;
    double scene = 0.0;
    std::string error;

    if (!initGpu(g)) {
        error = g.error.empty() ? "Vulkan başlatılamadı" : g.error;
    } else {
        compute = measureCompute(g);
        scene = measureScene(g);
        if (compute <= 0.0 && scene <= 0.0) {
            error = g.error.empty() ? "GPU ölçümü tamamlanamadı" : g.error;
        }
        shutdown(g);
    }

    std::string sCompute = compute > 0.0 ? fmt(compute, 2) : "";
    std::string sScene = scene > 0.0 ? fmt(scene, 1) : "";
    const bool usable = compute > 0.0 || scene > 0.0;

    jobjectArray result = env->NewObjectArray(6, env->FindClass("java/lang/String"), nullptr);
    env->SetObjectArrayElement(result, 0, jstr(env, usable ? "1" : "0"));
    env->SetObjectArrayElement(result, 1, jstr(env, g.name.empty() ? "—" : g.name));
    env->SetObjectArrayElement(result, 2, jstr(env, g.api.empty() ? "—" : g.api));
    env->SetObjectArrayElement(result, 3, jstr(env, sCompute));
    env->SetObjectArrayElement(result, 4, jstr(env, sScene));
    env->SetObjectArrayElement(result, 5, jstr(env, error));
    return result;
}

// ---------------------------------------------------------------------------
// GPU detail measurement for the validation screen.
//
// Renders kDetailFrames frames in one command buffer with a GPU timestamp query
// around every frame, then reads the final image back and compares five sampled
// pixels against the analytic result of the fragment shader. That proves the
// pipeline actually drew the scene instead of returning a blank surface.
//
// Returns: [supported, device, api, framesCsvNs, pixelOk, maxPixelError, note]
// ---------------------------------------------------------------------------

namespace {

constexpr uint32_t kDetailFrames = 120u;

double shaderReference(float fx, float fy, float t) {
    double pxc = fx / 512.0;
    double pyc = fy / 512.0;
    double acc = 0.0;
    for (int i = 0; i < 64; i++) {
        double fi = (double) i + 1.0;
        acc += std::sin(pxc * fi + t) * std::cos(pyc * fi - t) * 0.5;
        acc = acc * 0.999 + 0.001;
    }
    return acc * 0.5 + 0.5;
}

struct DetailResult {
    std::vector<uint64_t> frameNs;
    bool pixelOk = false;
    double maxPixelError = -1.0;
    std::string note;
};

bool renderDetail(Gpu &g, DetailResult &out) {
    // ---- image ------------------------------------------------------------
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {kSceneSize, kSceneSize, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkImage image = VK_NULL_HANDLE;
    if (vkCreateImage(g.device, &imageInfo, nullptr, &image) != VK_SUCCESS) {
        out.note = "vkCreateImage failed";
        return false;
    }
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(g.device, image, &req);
    uint32_t type = findMemoryType(g, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) type = findMemoryType(g, req.memoryTypeBits, 0);
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = type;
    VkDeviceMemory imageMem = VK_NULL_HANDLE;
    if (vkAllocateMemory(g.device, &alloc, nullptr, &imageMem) != VK_SUCCESS) {
        vkDestroyImage(g.device, image, nullptr);
        out.note = "image memory allocation failed";
        return false;
    }
    vkBindImageMemory(g.device, image, imageMem, 0);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageView view = VK_NULL_HANDLE;
    vkCreateImageView(g.device, &viewInfo, nullptr, &view);

    // ---- render pass ------------------------------------------------------
    VkAttachmentDescription color{};
    color.format = VK_FORMAT_R8G8B8A8_UNORM;
    color.samples = VK_SAMPLE_COUNT_1_BIT;
    color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    color.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

    VkAttachmentReference colorRef{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorRef;

    VkSubpassDependency dep{};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo rpInfo{};
    rpInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    rpInfo.attachmentCount = 1;
    rpInfo.pAttachments = &color;
    rpInfo.subpassCount = 1;
    rpInfo.pSubpasses = &subpass;
    rpInfo.dependencyCount = 1;
    rpInfo.pDependencies = &dep;
    VkRenderPass renderPass = VK_NULL_HANDLE;
    if (vkCreateRenderPass(g.device, &rpInfo, nullptr, &renderPass) != VK_SUCCESS) {
        out.note = "vkCreateRenderPass failed";
        vkDestroyImageView(g.device, view, nullptr);
        vkDestroyImage(g.device, image, nullptr);
        vkFreeMemory(g.device, imageMem, nullptr);
        return false;
    }

    VkFramebufferCreateInfo fbInfo{};
    fbInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fbInfo.renderPass = renderPass;
    fbInfo.attachmentCount = 1;
    fbInfo.pAttachments = &view;
    fbInfo.width = kSceneSize;
    fbInfo.height = kSceneSize;
    fbInfo.layers = 1;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    vkCreateFramebuffer(g.device, &fbInfo, nullptr, &framebuffer);

    // ---- pipeline ---------------------------------------------------------
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    push.offset = 0;
    push.size = sizeof(float);

    VkPipelineLayoutCreateInfo plInfo{};
    plInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plInfo.pushConstantRangeCount = 1;
    plInfo.pPushConstantRanges = &push;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    vkCreatePipelineLayout(g.device, &plInfo, nullptr, &layout);

    VkShaderModule vs = makeShader(g, vk_scene_vert_spv, vk_scene_vert_spv_size);
    VkShaderModule fs = makeShader(g, vk_scene_frag_spv, vk_scene_frag_spv_size);
    if (!vs || !fs) {
        out.note = "scene shader rejected";
        if (vs) vkDestroyShaderModule(g.device, vs, nullptr);
        if (fs) vkDestroyShaderModule(g.device, fs, nullptr);
        vkDestroyPipelineLayout(g.device, layout, nullptr);
        vkDestroyFramebuffer(g.device, framebuffer, nullptr);
        vkDestroyRenderPass(g.device, renderPass, nullptr);
        vkDestroyImageView(g.device, view, nullptr);
        vkDestroyImage(g.device, image, nullptr);
        vkFreeMemory(g.device, imageMem, nullptr);
        return false;
    }

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vs;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fs;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

    VkPipelineInputAssemblyStateCreateInfo ia{};
    ia.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkViewport viewport{0, 0, (float) kSceneSize, (float) kSceneSize, 0.0f, 1.0f};
    VkRect2D scissor{{0, 0}, {kSceneSize, kSceneSize}};
    VkPipelineViewportStateCreateInfo vp{};
    vp.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    vp.viewportCount = 1;
    vp.pViewports = &viewport;
    vp.scissorCount = 1;
    vp.pScissors = &scissor;

    VkPipelineRasterizationStateCreateInfo raster{};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo ms{};
    ms.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState blendAttachment{};
    blendAttachment.colorWriteMask = 0xF;
    VkPipelineColorBlendStateCreateInfo blend{};
    blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blend.attachmentCount = 1;
    blend.pAttachments = &blendAttachment;

    VkGraphicsPipelineCreateInfo gp{};
    gp.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    gp.stageCount = 2;
    gp.pStages = stages;
    gp.pVertexInputState = &vi;
    gp.pInputAssemblyState = &ia;
    gp.pViewportState = &vp;
    gp.pRasterizationState = &raster;
    gp.pMultisampleState = &ms;
    gp.pColorBlendState = &blend;
    gp.layout = layout;
    gp.renderPass = renderPass;
    gp.subpass = 0;

    VkPipeline pipeline = VK_NULL_HANDLE;
    VkResult pr = vkCreateGraphicsPipelines(g.device, VK_NULL_HANDLE, 1, &gp, nullptr, &pipeline);
    vkDestroyShaderModule(g.device, vs, nullptr);
    vkDestroyShaderModule(g.device, fs, nullptr);
    if (pr != VK_SUCCESS) {
        out.note = "vkCreateGraphicsPipelines failed";
        vkDestroyPipelineLayout(g.device, layout, nullptr);
        vkDestroyFramebuffer(g.device, framebuffer, nullptr);
        vkDestroyRenderPass(g.device, renderPass, nullptr);
        vkDestroyImageView(g.device, view, nullptr);
        vkDestroyImage(g.device, image, nullptr);
        vkFreeMemory(g.device, imageMem, nullptr);
        return false;
    }

    // ---- timestamp queries ------------------------------------------------
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(g.phys, &props);
    double ticksToNs = (double) props.limits.timestampPeriod;
    if (ticksToNs <= 0.0) ticksToNs = 1.0;
    const bool timestampsSupported =
        props.limits.timestampComputeAndGraphics != VK_FALSE;

    VkQueryPool queryPool = VK_NULL_HANDLE;
    if (timestampsSupported) {
        VkQueryPoolCreateInfo qp{};
        qp.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        qp.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qp.queryCount = kDetailFrames * 2;
        if (vkCreateQueryPool(g.device, &qp, nullptr, &queryPool) != VK_SUCCESS) {
            queryPool = VK_NULL_HANDLE;
        }
    }

    // ---- readback buffer --------------------------------------------------
    Buffer readback{};
    VkDeviceSize pixelBytes = (VkDeviceSize) kSceneSize * kSceneSize * 4;
    bool haveReadback = createBuffer(
        g, pixelBytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, readback);

    VkCommandBuffer cmd = beginOnce(g);
    if (!cmd) {
        out.note = "command buffer allocation failed";
        if (queryPool) vkDestroyQueryPool(g.device, queryPool, nullptr);
        if (haveReadback) destroyBuffer(g, readback);
        vkDestroyPipeline(g.device, pipeline, nullptr);
        vkDestroyPipelineLayout(g.device, layout, nullptr);
        vkDestroyFramebuffer(g.device, framebuffer, nullptr);
        vkDestroyRenderPass(g.device, renderPass, nullptr);
        vkDestroyImageView(g.device, view, nullptr);
        vkDestroyImage(g.device, image, nullptr);
        vkFreeMemory(g.device, imageMem, nullptr);
        return false;
    }

    if (queryPool) {
        vkCmdResetQueryPool(cmd, queryPool, 0, kDetailFrames * 2);
    }

    VkClearValue clear{};
    clear.color = {{0.05f, 0.06f, 0.08f, 1.0f}};
    VkRenderPassBeginInfo rbi{};
    rbi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rbi.renderPass = renderPass;
    rbi.framebuffer = framebuffer;
    rbi.renderArea = {{0, 0}, {kSceneSize, kSceneSize}};
    rbi.clearValueCount = 1;
    rbi.pClearValues = &clear;

    vkCmdBeginRenderPass(cmd, &rbi, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    for (uint32_t f = 0; f < kDetailFrames; f++) {
        float t = (float) f;
        if (queryPool) {
            vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, f * 2);
        }
        vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(float), &t);
        vkCmdDraw(cmd, 3, 1, 0, 0);
        if (queryPool) {
            vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, f * 2 + 1);
        }
    }
    vkCmdEndRenderPass(cmd);

    if (haveReadback) {
        VkBufferImageCopy region{};
        region.bufferOffset = 0;
        region.bufferRowLength = 0;
        region.bufferImageHeight = 0;
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.imageOffset = {0, 0, 0};
        region.imageExtent = {kSceneSize, kSceneSize, 1};
        vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               readback.buffer, 1, &region);
    }
    vkEndCommandBuffer(cmd);

    bool ok = submitAndWait(g, cmd);
    if (!ok) out.note = g.error;
    vkFreeCommandBuffers(g.device, g.pool, 1, &cmd);

    if (ok && queryPool) {
        std::vector<uint64_t> stamps(kDetailFrames * 2, 0);
        VkResult qr = vkGetQueryPoolResults(
            g.device, queryPool, 0, kDetailFrames * 2,
            stamps.size() * sizeof(uint64_t), stamps.data(), sizeof(uint64_t),
            VK_QUERY_RESULT_64_BIT);
        if (qr == VK_SUCCESS) {
            out.frameNs.reserve(kDetailFrames);
            for (uint32_t f = 0; f < kDetailFrames; f++) {
                uint64_t a = stamps[f * 2];
                uint64_t b = stamps[f * 2 + 1];
                if (b > a) out.frameNs.push_back((uint64_t) ((double) (b - a) * ticksToNs));
            }
        } else {
            out.note = "timestamp readback failed";
        }
    }

    // ---- pixel correctness ------------------------------------------------
    if (ok && haveReadback && readback.mapped) {
        const uint8_t *px = (const uint8_t *) readback.mapped;
        const float lastT = (float) (kDetailFrames - 1);
        const int probes[5][2] = {{64, 64}, {300, 200}, {511, 511}, {800, 900}, {1000, 40}};
        double worst = 0.0;
        bool valid = true;
        for (auto &p : probes) {
            float fx = (float) p[0] + 0.5f;
            float fy = (float) p[1] + 0.5f;
            size_t idx = ((size_t) p[1] * kSceneSize + (size_t) p[0]) * 4;
            double actual = px[idx] / 255.0;
            double expected = shaderReference(fx, fy, lastT);
            expected = std::min(1.0, std::max(0.0, expected));
            double err = std::fabs(actual - expected);
            if (err > worst) worst = err;
            // 8-bit quantisation plus shader precision: 0.03 is a tight bound
            if (err > 0.03) valid = false;
        }
        out.pixelOk = valid;
        out.maxPixelError = worst;
    } else if (out.note.empty()) {
        out.note = "readback unavailable";
    }

    if (queryPool) vkDestroyQueryPool(g.device, queryPool, nullptr);
    if (haveReadback) destroyBuffer(g, readback);
    vkDestroyPipeline(g.device, pipeline, nullptr);
    vkDestroyPipelineLayout(g.device, layout, nullptr);
    vkDestroyFramebuffer(g.device, framebuffer, nullptr);
    vkDestroyRenderPass(g.device, renderPass, nullptr);
    vkDestroyImageView(g.device, view, nullptr);
    vkDestroyImage(g.device, image, nullptr);
    vkFreeMemory(g.device, imageMem, nullptr);
    return true;
}

} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_alican_devicescope_bench_VulkanBench_nDetail(JNIEnv *env, jclass) {
    Gpu g;
    DetailResult detail;
    std::string note;

    if (!initGpu(g)) {
        note = g.error.empty() ? "Vulkan baslatilamadi" : g.error;
    } else {
        renderDetail(g, detail);
        if (!detail.note.empty()) note = detail.note;
        shutdown(g);
    }

    std::string frames;
    for (size_t i = 0; i < detail.frameNs.size(); i++) {
        if (i) frames += ',';
        frames += std::to_string(detail.frameNs[i]);
    }

    char errBuf[32];
    snprintf(errBuf, sizeof(errBuf), "%.4f", detail.maxPixelError < 0 ? 0.0 : detail.maxPixelError);

    jobjectArray result = env->NewObjectArray(7, env->FindClass("java/lang/String"), nullptr);
    if (!result) return nullptr;
    env->SetObjectArrayElement(result, 0, jstr(env, detail.frameNs.empty() ? "0" : "1"));
    env->SetObjectArrayElement(result, 1, jstr(env, g.name.empty() ? "-" : g.name));
    env->SetObjectArrayElement(result, 2, jstr(env, g.api.empty() ? "-" : g.api));
    env->SetObjectArrayElement(result, 3, jstr(env, frames));
    env->SetObjectArrayElement(result, 4, jstr(env, detail.pixelOk ? "1" : "0"));
    env->SetObjectArrayElement(result, 5, jstr(env, errBuf));
    env->SetObjectArrayElement(result, 6, jstr(env, note));
    return result;
}
