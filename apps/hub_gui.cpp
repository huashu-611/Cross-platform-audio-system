// audiohub-hub-gui：汇聚端图形界面。
//
// 纯 Win32 API，不引 MFC/WinUI/第三方库——和这个项目其它部分一样，
// 目标是"一个 exe 扔过去就能跑"。
//
// 界面刻意做得很朴素：这个程序的使用场景是"手机上放歌、PC 出声"，
// 用户真正需要的只有两件事——知道手机上该填什么地址，以及随时能把
// 某一路（通常是手机）的音量调小或静音。
#include <windows.h>
#include <commctrl.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <map>
#include <memory>
#include <string>

#include "audiohub/hub_core.hpp"

namespace {

constexpr int kMaxRows = 8;          // 同时显示的源上限，够用
constexpr int kRowHeight = 46;
constexpr int kRowsTop = 96;
constexpr int kRefreshMs = 200;

// 控件 ID
constexpr int IDC_ROW_LABEL = 1000;
constexpr int IDC_ROW_LEVEL = 1100;
constexpr int IDC_ROW_SLIDER = 1200;
constexpr int IDC_ROW_MUTE = 1300;
constexpr int IDC_MASTER_SLIDER = 1400;
constexpr int IDC_LAT_40 = 1501;
constexpr int IDC_LAT_60 = 1502;
constexpr int IDC_LAT_120 = 1503;

struct RowControls {
  HWND label = nullptr;
  HWND level = nullptr;
  HWND slider = nullptr;
  HWND mute = nullptr;
  uint16_t stream_id = 0;
  bool used = false;
};

class Gui {
 public:
  Gui() : hub_(new audiohub::HubCore()) {}

  int Run(HINSTANCE instance) {
    INITCOMMONCONTROLSEX icc{};
    icc.dwSize = sizeof(icc);
    // 注意：没有 ICC_TRACKBAR_CLASS 这个标志，trackbar 归 ICC_STANDARD_CLASSES 管
    icc.dwICC = ICC_STANDARD_CLASSES | ICC_LISTVIEW_CLASSES;
    InitCommonControlsEx(&icc);

    WNDCLASSEXA wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = &Gui::StaticWndProc;
    wc.hInstance = instance;
    wc.lpszClassName = "AudioHubGuiClass";
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_BTNFACE + 1);
    RegisterClassExA(&wc);

    const int width = 580;
    const int height = 620;
    const int screen_w = GetSystemMetrics(SM_CXSCREEN);
    const int screen_h = GetSystemMetrics(SM_CYSCREEN);

    hwnd_ = CreateWindowExA(0, "AudioHubGuiClass", "AudioHub 汇聚端",
                            WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
                            (screen_w - width) / 2, (screen_h - height) / 2, width, height,
                            nullptr, nullptr, instance, this);
    if (hwnd_ == nullptr) return 1;

    ShowWindow(hwnd_, SW_SHOW);
    UpdateWindow(hwnd_);

    MSG msg;
    while (GetMessageA(&msg, nullptr, 0, 0)) {
      TranslateMessage(&msg);
      DispatchMessageA(&msg);
    }
    return static_cast<int>(msg.wParam);
  }

 private:
  // ------------------------------------------------------------ 窗口过程

  static LRESULT CALLBACK StaticWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (msg == WM_NCCREATE) {
      auto* self = reinterpret_cast<Gui*>(
          reinterpret_cast<CREATESTRUCTA*>(lp)->lpCreateParams);
      SetWindowLongPtrA(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
      self->hwnd_ = hwnd;
    }
    auto* self = reinterpret_cast<Gui*>(GetWindowLongPtrA(hwnd, GWLP_USERDATA));
    if (self == nullptr) return DefWindowProcA(hwnd, msg, wp, lp);
    return self->WndProc(hwnd, msg, wp, lp);
  }

  LRESULT WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
      case WM_CREATE:
        CreateControls(hwnd);
        hub_->SetEventCallback([this](const std::string& text) {
          // 内部线程触发，只是记下来，由 UI 定时器统一显示
          last_event_ = text;
        });
        StartHub();
        SetTimer(hwnd, 1, kRefreshMs, nullptr);
        return 0;

      case WM_TIMER:
        Refresh();
        return 0;

      case WM_HSCROLL: {
        HWND from = reinterpret_cast<HWND>(lp);
        if (from == master_slider_) {
          const int pos = SendMessageA(master_slider_, TBM_GETPOS, 0, 0);
          hub_->SetMasterGain(pos / 100.0f);
          char buf[32];
          std::snprintf(buf, sizeof(buf), "总音量 %d%%", pos);
          SetWindowTextA(master_label_, buf);
        } else {
          for (auto& row : rows_) {
            if (from == row.slider && row.used) {
              const int pos = SendMessageA(row.slider, TBM_GETPOS, 0, 0);
              hub_->SetSourceGain(row.stream_id, pos / 100.0f);
              remembered_gain_[row.stream_id] = pos / 100.0f;
              char buf[32];
              std::snprintf(buf, sizeof(buf), "%d%%", pos);
              SetWindowTextA(row.level, buf);
              break;
            }
          }
        }
        return 0;
      }

      case WM_COMMAND: {
        const int id = LOWORD(wp);
        if (id == IDC_LAT_40 || id == IDC_LAT_60 || id == IDC_LAT_120) {
          hub_->SetLatency(id == IDC_LAT_40 ? 40 : (id == IDC_LAT_60 ? 60 : 120));
          Refresh();
        }
        for (auto& row : rows_) {
          if (row.used && reinterpret_cast<HWND>(lp) == row.mute &&
              HIWORD(wp) == BN_CLICKED) {
            const bool muted = SendMessageA(row.mute, BM_GETCHECK, 0, 0) == BST_CHECKED;
            hub_->SetSourceMuted(row.stream_id, muted);
            break;
          }
        }
        return 0;
      }

      case WM_DESTROY:
        KillTimer(hwnd, 1);
        hub_->Stop();
        PostQuitMessage(0);
        return 0;

      default:
        return DefWindowProcA(hwnd, msg, wp, lp);
    }
  }

  // ------------------------------------------------------------ 控件

  void CreateControls(HWND hwnd) {
    ip_label_ = CreateWindowExA(0, "STATIC", "", WS_CHILD | WS_VISIBLE, 16, 12, 544, 20,
                                hwnd, nullptr, nullptr, nullptr);
    status_label_ = CreateWindowExA(0, "STATIC", "启动中…", WS_CHILD | WS_VISIBLE, 16, 36, 544,
                                    20, hwnd, nullptr, nullptr, nullptr);

    // 每路一行
    for (int i = 0; i < kMaxRows; ++i) {
      RowControls& row = rows_[i];
      const int y = kRowsTop + i * kRowHeight;
      char label[32];
      std::snprintf(label, sizeof(label), "流 %d", i + 1);
      row.label = CreateWindowExA(0, "STATIC", label, WS_CHILD, 16, y + 8, 44, 18, hwnd,
                                  reinterpret_cast<HMENU>(IDC_ROW_LABEL + i), nullptr, nullptr);
      row.level = CreateWindowExA(0, "STATIC", "100%", WS_CHILD, 62, y + 8, 52, 18, hwnd,
                                  reinterpret_cast<HMENU>(IDC_ROW_LEVEL + i), nullptr, nullptr);
      row.slider = CreateWindowExA(0, TRACKBAR_CLASSA, "",
                                    WS_CHILD | TBS_HORZ | TBS_NOTICKS, 118, y + 4, 250, 26,
                                    hwnd, reinterpret_cast<HMENU>(IDC_ROW_SLIDER + i), nullptr,
                                    nullptr);
      SendMessageA(row.slider, TBM_SETRANGE, TRUE, MAKELPARAM(0, 100));
      SendMessageA(row.slider, TBM_SETPOS, TRUE, 100);
      row.mute = CreateWindowExA(0, "BUTTON", "静音", WS_CHILD | BS_AUTOCHECKBOX, 384, y + 6,
                                 70, 22, hwnd, reinterpret_cast<HMENU>(IDC_ROW_MUTE + i),
                                 nullptr, nullptr);
    }

    const int master_y = kRowsTop + kMaxRows * kRowHeight + 8;
    CreateWindowExA(0, "STATIC", "总音量", WS_CHILD | WS_VISIBLE, 16, master_y + 8, 52, 18,
                    hwnd, nullptr, nullptr, nullptr);
    master_slider_ = CreateWindowExA(0, TRACKBAR_CLASSA, "",
                                     WS_CHILD | WS_VISIBLE | TBS_HORZ | TBS_NOTICKS, 76,
                                     master_y, 292, 26, hwnd,
                                     reinterpret_cast<HMENU>(IDC_MASTER_SLIDER), nullptr,
                                     nullptr);
    SendMessageA(master_slider_, TBM_SETRANGE, TRUE, MAKELPARAM(0, 100));
    SendMessageA(master_slider_, TBM_SETPOS, TRUE, 100);
    master_label_ = CreateWindowExA(0, "STATIC", "总音量 100%", WS_CHILD | WS_VISIBLE, 380,
                                    master_y + 8, 120, 18, hwnd, nullptr, nullptr, nullptr);

    const int lat_y = master_y + 36;
    CreateWindowExA(0, "STATIC", "目标延迟", WS_CHILD | WS_VISIBLE, 16, lat_y + 6, 60, 18,
                    hwnd, nullptr, nullptr, nullptr);
    CreateWindowExA(0, "BUTTON", "40 ms", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 80, lat_y, 74,
                    24, hwnd, reinterpret_cast<HMENU>(IDC_LAT_40), nullptr, nullptr);
    CreateWindowExA(0, "BUTTON", "60 ms", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 162, lat_y, 74,
                    24, hwnd, reinterpret_cast<HMENU>(IDC_LAT_60), nullptr, nullptr);
    CreateWindowExA(0, "BUTTON", "120 ms", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 244, lat_y,
                    74, 24, hwnd, reinterpret_cast<HMENU>(IDC_LAT_120), nullptr, nullptr);

    log_label_ = CreateWindowExA(0, "STATIC", "", WS_CHILD | WS_VISIBLE, 16, lat_y + 36, 544,
                                 40, hwnd, nullptr, nullptr, nullptr);
  }

  void StartHub() {
    std::string error;
    if (!hub_->Start(47000, 60, 10, &error)) {
      SetWindowTextA(status_label_, error.c_str());
      return;
    }
    started_ = true;
    const std::string text = "在手机上填: " + hub_->local_ip() + " : 47000";
    SetWindowTextA(ip_label_, text.c_str());
  }

  // ------------------------------------------------------------ 刷新

  void Refresh() {
    if (!started_) return;

    const auto stats = hub_->Stats();
    const auto sources = hub_->Sources();

    char status[256];
    std::snprintf(status, sizeof(status), "源 %zu 路 · 总线 %.1f dBFS · 延迟 %d ms · 坏包 %llu",
                  sources.size(), stats.bus_level_dbfs, stats.latency_ms,
                  static_cast<unsigned long long>(stats.malformed_packets));
    SetWindowTextA(status_label_, status);

    // 按 id 排序，保证行的顺序稳定，不会每 200 ms 跳一次
    auto sorted = sources;
    std::sort(sorted.begin(), sorted.end(),
              [](const audiohub::SourceInfo& a, const audiohub::SourceInfo& b) {
                return a.id < b.id;
              });

    for (int i = 0; i < kMaxRows; ++i) {
      RowControls& row = rows_[i];
      if (i < static_cast<int>(sorted.size())) {
        const auto& s = sorted[i];
        const bool is_new = !row.used || row.stream_id != s.id;
        row.used = true;
        row.stream_id = s.id;

        char label[48];
        std::snprintf(label, sizeof(label), "流 %u", s.id);
        SetWindowTextA(row.label, label);

        // 只在"换了一路"或者用户没在拖的时候更新数值，
        // 否则用户正在拖动滑杆会被刷新抢回去。
        if (is_new) {
          const float remembered = remembered_gain_.count(s.id) ? remembered_gain_[s.id] : s.gain;
          const int pos = static_cast<int>(remembered * 100.0f + 0.5f);
          SendMessageA(row.slider, TBM_SETPOS, TRUE, pos);
          SendMessageA(row.mute, BM_SETCHECK, s.muted ? BST_CHECKED : BST_UNCHECKED, 0);
        }
        char level[48];
        std::snprintf(level, sizeof(level), "%.1f dBFS", s.level_dbfs);
        SetWindowTextA(row.level, level);

        ShowWindow(row.label, SW_SHOW);
        ShowWindow(row.level, SW_SHOW);
        ShowWindow(row.slider, SW_SHOW);
        ShowWindow(row.mute, SW_SHOW);
      } else if (row.used) {
        row.used = false;
        ShowWindow(row.label, SW_HIDE);
        ShowWindow(row.level, SW_HIDE);
        ShowWindow(row.slider, SW_HIDE);
        ShowWindow(row.mute, SW_HIDE);
      }
    }

    if (!last_event_.empty()) {
      SetWindowTextA(log_label_, last_event_.c_str());
      last_event_.clear();
    }
  }

  HWND hwnd_ = nullptr;
  HWND ip_label_ = nullptr;
  HWND status_label_ = nullptr;
  HWND master_slider_ = nullptr;
  HWND master_label_ = nullptr;
  HWND log_label_ = nullptr;
  RowControls rows_[kMaxRows];
  std::map<uint16_t, float> remembered_gain_;
  std::string last_event_;
  bool started_ = false;
  std::unique_ptr<audiohub::HubCore> hub_;
};

}  // namespace

int WINAPI WinMain(HINSTANCE instance, HINSTANCE, LPSTR, int) {
  Gui gui;
  return gui.Run(instance);
}
