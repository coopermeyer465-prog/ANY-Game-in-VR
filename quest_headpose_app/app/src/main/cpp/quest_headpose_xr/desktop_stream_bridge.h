#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace DesktopStreamBridge {

void UpdateLatestFrame(const uint8_t* pixels, int width, int height);
bool CopyLatestFrame(std::vector<uint8_t>& pixels, int& width, int& height, uint64_t& version);
void SetStreamStatus(bool connected, const std::string& message);

}  // namespace DesktopStreamBridge
