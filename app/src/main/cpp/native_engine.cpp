#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <vulkan/vulkan.h>

namespace {
constexpr const char* kTag = "Neuz3D";

std::string detectVulkan() {
    // Detect the platform Vulkan loader and enumerate devices without claiming that
    // a Vulkan renderer/swapchain has been created.
    void* loader = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!loader) return "Vulkan loader unavailable";
    auto enumerate = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        dlsym(loader, "vkEnumerateInstanceVersion"));
    uint32_t apiVersion = VK_API_VERSION_1_0;
    if (enumerate) {
        if (enumerate(&apiVersion) != VK_SUCCESS) apiVersion = VK_API_VERSION_1_0;
    }
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "Neuz 3D";
    appInfo.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    appInfo.pEngineName = "Neuz Native Core";
    appInfo.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    appInfo.apiVersion = apiVersion;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    VkInstance instance = VK_NULL_HANDLE;
    const VkResult created = vkCreateInstance(&createInfo, nullptr, &instance);
    if (created != VK_SUCCESS) {
        dlclose(loader);
        return "Vulkan loader present; instance creation failed (" +
               std::to_string(static_cast<int>(created)) + ")";
    }

    uint32_t count = 0;
    VkResult result = vkEnumeratePhysicalDevices(instance, &count, nullptr);
    std::string answer;
    if (result == VK_SUCCESS && count > 0) {
        std::vector<VkPhysicalDevice> devices(count);
        result = vkEnumeratePhysicalDevices(instance, &count, devices.data());
        if (result == VK_SUCCESS && count > 0) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(devices.front(), &properties);
            answer = "Vulkan device detected: ";
            answer += properties.deviceName;
            answer += " (API " + std::to_string(VK_VERSION_MAJOR(properties.apiVersion)) +
                      "." + std::to_string(VK_VERSION_MINOR(properties.apiVersion)) + ")";
        }
    }
    if (answer.empty()) answer = "Vulkan loader present; no usable physical device enumerated";
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
