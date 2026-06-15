#include "desktop_stream_bridge.h"

#include "common.h"

#include <mutex>

namespace DesktopStreamBridge {
namespace {

std::mutex g_frameMutex;
std::vector<uint8_t> g_latestFrame;
int g_latestWidth = 0;
int g_latestHeight = 0;
uint64_t g_latestVersion = 0;
bool g_streamConnected = false;
std::string g_streamStatus = "Desktop stream idle";

}  // namespace

void UpdateLatestFrame(const uint8_t* pixels, int width, int height) {
    if (pixels == nullptr || width <= 0 || height <= 0) {
        return;
    }

    const size_t byteCount = static_cast<size_t>(width) * static_cast<size_t>(height) * 4u;
    std::lock_guard<std::mutex> lock(g_frameMutex);
    g_latestFrame.assign(pixels, pixels + byteCount);
    g_latestWidth = width;
    g_latestHeight = height;
    ++g_latestVersion;
}

bool CopyLatestFrame(std::vector<uint8_t>& pixels, int& width, int& height, uint64_t& version) {
    std::lock_guard<std::mutex> lock(g_frameMutex);
    if (g_latestFrame.empty() || g_latestWidth <= 0 || g_latestHeight <= 0) {
        return false;
    }

    pixels = g_latestFrame;
    width = g_latestWidth;
    height = g_latestHeight;
    version = g_latestVersion;
    return true;
}

void SetStreamStatus(bool connected, const std::string& message) {
    std::lock_guard<std::mutex> lock(g_frameMutex);
    g_streamConnected = connected;
    g_streamStatus = message;
    Log::Write(Log::Level::Info,
               Fmt("Desktop stream status connected=%d message=%s", connected ? 1 : 0, message.c_str()));
}

}  // namespace DesktopStreamBridge
