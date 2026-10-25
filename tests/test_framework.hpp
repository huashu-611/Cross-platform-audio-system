// 极简测试框架。不引第三方依赖，是为了让 `cmake --build` 之后
// 直接 `ctest` 就能跑，不需要联网拉 gtest。
#pragma once

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

namespace test {

using Fn = void (*)();

struct Case {
  const char* name;
  Fn fn;
};

std::vector<Case>& Registry();

struct Registrar {
  Registrar(const char* name, Fn fn) { Registry().push_back(Case{name, fn}); }
};

extern int g_checks;
extern int g_failures;
extern const char* g_current_case;

void Fail(const char* file, int line, const std::string& message);

}  // namespace test

#define TEST(name)                                                    \
  static void name();                                                 \
  static ::test::Registrar test_reg_##name(#name, &name);             \
  static void name()

#define CHECK_TRUE(cond)                                                          \
  do {                                                                            \
    ++::test::g_checks;                                                           \
    if (!(cond)) ::test::Fail(__FILE__, __LINE__, "CHECK_TRUE(" #cond ")");        \
  } while (0)

#define CHECK_FALSE(cond)                                                         \
  do {                                                                            \
    ++::test::g_checks;                                                           \
    if ((cond)) ::test::Fail(__FILE__, __LINE__, "CHECK_FALSE(" #cond ")");        \
  } while (0)

#define CHECK_EQ(a, b)                                                            \
  do {                                                                            \
    ++::test::g_checks;                                                           \
    const auto va = (a);                                                          \
    const auto vb = (b);                                                          \
    if (!(va == vb)) {                                                            \
      ::test::Fail(__FILE__, __LINE__,                                            \
                   std::string("CHECK_EQ(" #a ", " #b "): ") + std::to_string(va) + \
                       " != " + std::to_string(vb));                          \
    }                                                                             \
  } while (0)

#define CHECK_NEAR(a, b, tol)                                                     \
  do {                                                                            \
    ++::test::g_checks;                                                           \
    const double va = static_cast<double>(a);                                     \
    const double vb = static_cast<double>(b);                                     \
    if (std::fabs(va - vb) > (tol)) {                                             \
      ::test::Fail(__FILE__, __LINE__,                                            \
                   std::string("CHECK_NEAR(" #a ", " #b "): ") + std::to_string(va) + \
                       " vs " + std::to_string(vb));                          \
    }                                                                             \
  } while (0)
