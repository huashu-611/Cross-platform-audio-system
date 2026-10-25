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

  size_t failed_cases = 0;
  for (const auto& c : test::Registry()) {
    test::g_current_case = c.name;
    const int before = test::g_failures;
    std::printf("- %s\n", c.name);
    c.fn();
    if (test::g_failures > before) ++failed_cases;
  }

  std::printf("\n断言 %d 条，失败 %d 条，用例失败 %zu/%zu\n", test::g_checks, test::g_failures,
              failed_cases, test::Registry().size());
  return test::g_failures == 0 ? 0 : 1;
}
