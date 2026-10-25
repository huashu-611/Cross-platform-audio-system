#include "audiohub/discovery.hpp"

#ifdef _WIN32

#include <winsock2.h>
#include <ws2tcpip.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <map>
#include <thread>
#include <vector>

#ifdef _MSC_VER
#pragma comment(lib, "ws2_32.lib")
#endif

namespace audiohub {
namespace {

constexpr uint16_t kDiscoveryPort = 47300;
constexpr const char* kMagic = "AUDIOHUB";
constexpr int kPeerTimeoutMs = 5000;

uint64_t NowMs() {
  return static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now().time_since_epoch())
          .count());
}

std::string BuildBeacon(const std::string& local_ip, uint16_t audio_port, uint16_t http_port,
                        bool can_send, bool can_receive, const std::string& name) {
  char role[3] = {can_send ? 'S' : '-', can_receive ? 'R' : '-', '\0'};
  char buf[512];
  std::snprintf(buf, sizeof(buf), "%s|1|%s|%u|%u|%s|%s\n", kMagic, role, audio_port, http_port,
                local_ip.c_str(), name.c_str());
  return buf;
}

}  // namespace

struct Discovery::Impl {
  SOCKET sock = INVALID_SOCKET;
  std::atomic<bool> stop{false};
  std::thread beacon_thread;
  std::thread recv_thread;

  std::string name;
  std::string local_ip;
  uint16_t audio_port = 0;
  uint16_t http_port = 0;
  bool advertise_can_send = true;
  bool advertise_can_receive = false;

  mutable std::mutex mutex;
  std::map<std::string, Peer> peers;  // key = ip:audio_port

  void SendBeacon() {
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(kDiscoveryPort);
    addr.sin_addr.s_addr = htonl(INADDR_BROADCAST);
    const std::string f = BuildBeacon(local_ip, audio_port, http_port, advertise_can_send,
                                      advertise_can_receive, name);
    ::sendto(sock, f.data(), static_cast<int>(f.size()), 0, reinterpret_cast<sockaddr*>(&addr),
             sizeof(addr));
  }

  void SendProbe() {
    const std::string f = std::string(kMagic) + "|1|WHO\n";
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(kDiscoveryPort);
    addr.sin_addr.s_addr = htonl(INADDR_BROADCAST);
    ::sendto(sock, f.data(), static_cast<int>(f.size()), 0, reinterpret_cast<sockaddr*>(&addr),
             sizeof(addr));
  }

  void HandleFrame(const std::string& line, const std::string& from_ip) {
    // 拆 | 分隔的字段；显示名是最后一段，所以可以含任意字符（含空格和 | 之外的东西）
    std::vector<std::string> parts;
    size_t start = 0;
    while (true) {
      const size_t pos = line.find('|', start);
      if (pos == std::string::npos) {
        parts.push_back(line.substr(start));
        break;
      }
      parts.push_back(line.substr(start, pos - start));
      start = pos + 1;
    }
    if (parts.size() < 7 || parts[0] != kMagic) return;

    // parts[2] 是角色串，形如 "SR" / "S-" / "-R"
    const std::string& r = parts[2];
    if (r == "WHO") return;

    Peer peer;
    peer.can_send = r.find('S') != std::string::npos;
    peer.can_receive = r.find('R') != std::string::npos;
    peer.audio_port = static_cast<uint16_t>(std::stoi(parts[3]));
    peer.http_port = static_cast<uint16_t>(std::stoi(parts[4]));
    peer.ip = parts[5].empty() ? from_ip : parts[5];
    peer.name = parts[6];
    peer.last_seen_ms = NowMs();

    const std::string key = peer.ip + ":" + std::to_string(peer.audio_port);
    std::lock_guard<std::mutex> lock(mutex);
    peers[key] = peer;
  }

  void RecvLoop() {
    char buf[1024];
    while (!stop.load()) {
      sockaddr_in from{};
      int from_len = sizeof(from);
      const int n = static_cast<int>(::recvfrom(sock, buf, sizeof(buf) - 1, 0,
                                                reinterpret_cast<sockaddr*>(&from), &from_len));
      if (n <= 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        continue;
      }
      buf[n] = '\0';
      char ip_text[INET_ADDRSTRLEN] = {0};
      ::inet_ntop(AF_INET, &from.sin_addr, ip_text, sizeof(ip_text));

      // 一帧里可能有换行分隔的多行
      std::string data(buf);
      size_t start = 0;
      while (true) {
        const size_t pos = data.find('\n', start);
        const std::string line = data.substr(start, pos == std::string::npos ? std::string::npos : pos - start);
        if (!line.empty()) HandleFrame(line, ip_text);
        if (pos == std::string::npos) break;
        start = pos + 1;
      }
    }
  }

  void BeaconLoop() {
    while (!stop.load()) {
      SendBeacon();
      std::this_thread::sleep_for(std::chrono::seconds(1));
    }
  }
};

Discovery::Discovery() : impl_(new Impl()) {
  WSADATA wsa;
  WSAStartup(MAKEWORD(2, 2), &wsa);
}

Discovery::~Discovery() { Stop(); }

bool Discovery::StartAdvertise(const std::string& display_name, const std::string& local_ip,
                              uint16_t audio_port, uint16_t http_port, bool can_receive,
                              bool can_send) {
  if (running_.load()) return false;
  impl_->name = display_name;
  impl_->local_ip = local_ip;
  impl_->audio_port = audio_port;
  impl_->http_port = http_port;
  impl_->advertise_can_send = can_send;
  impl_->advertise_can_receive = can_receive;
  StartBrowse();
  impl_->beacon_thread = std::thread([this] { impl_->BeaconLoop(); });
  return true;
}

bool Discovery::StartBrowse() {
  if (running_.load()) return true;

  impl_->sock = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (impl_->sock == INVALID_SOCKET) return false;

  int one = 1;
  ::setsockopt(impl_->sock, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&one),
               sizeof(one));
  ::setsockopt(impl_->sock, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&one),
               sizeof(one));

  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(kDiscoveryPort);
  addr.sin_addr.s_addr = htonl(INADDR_ANY);
  if (::bind(impl_->sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
    // 端口被占（同机上另一个实例）：发现功能不可用，但不该让程序起不来
    ::closesocket(impl_->sock);
    impl_->sock = INVALID_SOCKET;
    return false;
  }

  impl_->stop.store(false);
  running_.store(true);
  impl_->recv_thread = std::thread([this] { impl_->RecvLoop(); });
  return true;
}

void Discovery::Stop() {
  if (!running_.load()) return;
  running_.store(false);
  impl_->stop.store(true);
  if (impl_->sock != INVALID_SOCKET) {
    ::closesocket(impl_->sock);
    impl_->sock = INVALID_SOCKET;
  }
  if (impl_->beacon_thread.joinable()) impl_->beacon_thread.join();
  if (impl_->recv_thread.joinable()) impl_->recv_thread.join();
  std::lock_guard<std::mutex> lock(impl_->mutex);
  impl_->peers.clear();
}

std::vector<Peer> Discovery::Peers() const {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  const uint64_t now = NowMs();
  std::vector<Peer> out;
  for (auto it = impl_->peers.begin(); it != impl_->peers.end();) {
    if (now - it->second.last_seen_ms > kPeerTimeoutMs) {
      it = impl_->peers.erase(it);
    } else {
      out.push_back(it->second);
      ++it;
    }
  }
  std::sort(out.begin(), out.end(),
            [](const Peer& a, const Peer& b) { return a.name < b.name; });
  return out;
}

void Discovery::Probe() {
  if (impl_->sock != INVALID_SOCKET) impl_->SendProbe();
}

}  // namespace audiohub

#else

namespace audiohub {
struct Discovery::Impl {};
Discovery::Discovery() : impl_(new Impl()) {}
Discovery::~Discovery() = default;
bool Discovery::StartAdvertise(const std::string&, uint16_t, uint16_t, bool, bool) { return false; }
bool Discovery::StartBrowse() { return false; }
void Discovery::Stop() {}
std::vector<Peer> Discovery::Peers() const { return {}; }
void Discovery::Probe() {}
}  // namespace audiohub

#endif  // _WIN32
