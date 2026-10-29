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

    auto enumPhysDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
        dlsym(loader, "vkEnumeratePhysicalDevices"));
    auto getPhysDevProps = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
        dlsym(loader, "vkGetPhysicalDeviceProperties"));
    auto getPhysDevQueueFamilyProps = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
        dlsym(loader, "vkGetPhysicalDeviceQueueFamilyProperties"));
    auto getPhysDevMemProps = reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties>(
        dlsym(loader, "vkGetPhysicalDeviceMemoryProperties"));

    uint32_t count = 0;
    VkResult result = enumPhysDevices ? enumPhysDevices(instance, &count, nullptr) : VK_ERROR_INITIALIZATION_FAILED;
    std::vector<VkPhysicalDevice> devices;
    std::string answer;
    if (result == VK_SUCCESS && count > 0 && enumPhysDevices && getPhysDevProps) {
        devices.resize(count);
        result = enumPhysDevices(instance, &count, devices.data());
        if (result == VK_SUCCESS && count > 0) {
            VkPhysicalDeviceProperties props{};
            getPhysDevProps(devices.front(), &props);

            answer = "Vulkan device: ";
            answer += props.deviceName;
            answer += " (device API " + apiVersionToString(props.apiVersion) + ", driver " +
                      apiVersionToString(props.driverVersion) + ")";

            answer += " | Instance API " + apiVersionToString(maxApiVersion);
            answer += " | v1.1 " + std::string(supportsV1_1 ? "yes" : "no");
            answer += " | v1.2 " + std::string(supportsV1_2 ? "yes" : "no");
            answer += " | v1.3 " + std::string(supportsV1_3 ? "yes" : "no");

            if (getPhysDevQueueFamilyProps) {
                uint32_t qfpCount = 0;
                getPhysDevQueueFamilyProps(devices.front(), &qfpCount, nullptr);
                std::vector<VkQueueFamilyProperties> qfp(qfpCount);
                getPhysDevQueueFamilyProps(devices.front(), &qfpCount, qfp.data());
                bool hasGraphics = false;
                for (const auto& q : qfp) {
                    if (q.queueFlags & VK_QUEUE_GRAPHICS_BIT) { hasGraphics = true; break; }
                }
                answer += " | graphics queue: " + std::string(hasGraphics ? "yes" : "no");
            }

            if (getPhysDevMemProps) {
                VkPhysicalDeviceMemoryProperties memProps{};
                getPhysDevMemProps(devices.front(), &memProps);
                answer += " | memory heaps: " + std::to_string(memProps.memoryHeapCount);
            }
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
