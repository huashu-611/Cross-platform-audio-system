#include <cstdio>
#include <cstring>

#include "test_framework.hpp"

namespace test {
int g_checks = 0;
int g_failures = 0;
const char* g_current_case = "";

std::vector<Case>& Registry() {
  static std::vector<Case> cases;
  return cases;
}

void Fail(const char* file, int line, const std::string& message) {
  ++g_failures;
  std::printf("  [FAIL] %s:%d  %s\n", file, line, message.c_str());
}

}  // namespace test

int main() {
  std::printf("运行 %zu 个用例\n\n", test::Registry().size());
  std::fflush(stdout);

  size_t failed_cases = 0;
  for (const auto& c : test::Registry()) {
    test::g_current_case = c.name;
    const int before = test::g_failures;
    std::printf("- %s\n", c.name);
    // 每个用例前冲一次：某个用例崩溃时，最后一行就是崩在哪个用例（stdout 重定向到
    // 文件/管道时是全缓冲，不冲的话崩溃会连"崩在哪"都看不到）。
    std::fflush(stdout);
    c.fn();
    if (test::g_failures > before) ++failed_cases;
  }

  std::printf("\n断言 %d 条，失败 %d 条，用例失败 %zu/%zu\n", test::g_checks, test::g_failures,
              failed_cases, test::Registry().size());
  return test::g_failures == 0 ? 0 : 1;
}
