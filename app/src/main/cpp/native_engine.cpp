#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <vulkan/vulkan.h>

namespace {
constexpr const char* kTag = "Neuz3D";

std::string apiVersionToString(uint32_t apiVersion) {
    return std::to_string(VK_VERSION_MAJOR(apiVersion)) + "." +
           std::to_string(VK_VERSION_MINOR(apiVersion)) + "." +
           std::to_string(VK_VERSION_PATCH(apiVersion));
}

std::string detectVulkan() {
    void* loader = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!loader) return "Vulkan loader unavailable";

    auto enumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        dlsym(loader, "vkEnumerateInstanceVersion"));
    uint32_t maxApiVersion = VK_API_VERSION_1_0;
    if (enumerateInstanceVersion) {
        if (enumerateInstanceVersion(&maxApiVersion) != VK_SUCCESS) maxApiVersion = VK_API_VERSION_1_0;
    }

    bool supportsV1_1 = (maxApiVersion >= VK_API_VERSION_1_1);
    bool supportsV1_2 = (maxApiVersion >= VK_API_VERSION_1_2);
    bool supportsV1_3 = (maxApiVersion >= VK_API_VERSION_1_3);

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "Neuz 3D";
    appInfo.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    appInfo.pEngineName = "Neuz Native Core";
    appInfo.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    appInfo.apiVersion = maxApiVersion;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    VkInstance instance = VK_NULL_HANDLE;
    const VkResult created = vkCreateInstance(&createInfo, nullptr, &instance);
    if (created != VK_SUCCESS) {
        std::string msg = "Vulkan loader present (max API " + apiVersionToString(maxApiVersion) +
                          "); instance creation failed (" + std::to_string(static_cast<int>(created)) + ")";
        dlclose(loader);
        return msg;
    }

    uint32_t count = 0;
    VkResult result = vkEnumeratePhysicalDevices(instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices;
    std::string answer;
    if (result == VK_SUCCESS && count > 0) {
        devices.resize(count);
        result = vkEnumeratePhysicalDevices(instance, &count, devices.data());
        if (result == VK_SUCCESS && count > 0) {
            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(devices.front(), &props);

            answer = "Vulkan device: ";
            answer += props.deviceName;
            answer += " (device API " + apiVersionToString(props.apiVersion) + ", driver " +
                      apiVersionToString(props.driverVersion) + ")";

            VkPhysicalDeviceFeatures2 features2{};
            features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
            vkGetPhysicalDeviceFeatures2(devices.front(), &features2);

            answer += " | Instance API " + apiVersionToString(maxApiVersion);
            answer += " | v1.1 " + std::string(supportsV1_1 ? "yes" : "no");
            answer += " | v1.2 " + std::string(supportsV1_2 ? "yes" : "no");
            answer += " | v1.3 " + std::string(supportsV1_3 ? "yes" : "no");

            uint32_t qfpCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(devices.front(), &qfpCount, nullptr);
            std::vector<VkQueueFamilyProperties> qfp(qfpCount);
            vkGetPhysicalDeviceQueueFamilyProperties(devices.front(), &qfpCount, qfp.data());
            bool hasGraphics = false;
            for (const auto& q : qfp) {
                if (q.queueFlags & VK_QUEUE_GRAPHICS_BIT) { hasGraphics = true; break; }
            }
            answer += " | graphics queue: " + std::string(hasGraphics ? "yes" : "no");

            VkPhysicalDeviceMemoryProperties memProps{};
            vkGetPhysicalDeviceMemoryProperties(devices.front(), &memProps);
            answer += " | memory heaps: " + std::to_string(memProps.memoryHeapCount);
        }
    }
    if (answer.empty()) {
        answer = "Vulkan loader present (max API " + apiVersionToString(maxApiVersion) +
                 "); no usable physical device enumerated";
    }
    vkDestroyInstance(instance, nullptr);
    dlclose(loader);
    return answer;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_neuz3d_editor_MainActivity_nativeEngineInfo(JNIEnv* env, jobject) {
    const std::string info =
        "Neuz 3D native core: C++20 JNI loaded. " + detectVulkan() +
        ". Vulkan device detection is not a Vulkan rendering backend; the current "
        "visible viewport uses OpenGL ES.";
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", info.c_str());
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neuz3d_editor_MainActivity_nativeVulkanInfo(JNIEnv* env, jobject) {
    const std::string info = detectVulkan();
    __android_log_print(ANDROID_LOG_INFO, kTag, "Vulkan probe: %s", info.c_str());
    return env->NewStringUTF(info.c_str());
}
