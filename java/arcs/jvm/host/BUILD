load("//third_party/java/arcs/build_defs:build_defs.bzl", "arcs_kt_jvm_library")

licenses(["notice"])

package(default_visibility = ["//java/arcs:allowed-packages"])

arcs_kt_jvm_library(
    name = "host",
    srcs = glob(["*.kt"]),
    disable_lint_checks = ["NewApi"],
    deps = [
        "//java/arcs/core/data",
        "//java/arcs/core/host",
        "//java/arcs/core/host/api",
        "//java/arcs/core/storage/api",
        "//java/arcs/core/util",
        "//java/arcs/jvm/util",
        "//third_party/kotlin/kotlinx_atomicfu",
        "//third_party/kotlin/kotlinx_coroutines",
    ],
)
