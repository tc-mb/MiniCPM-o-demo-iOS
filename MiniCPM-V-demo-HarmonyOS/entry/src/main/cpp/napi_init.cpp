// Module registration entry for the HarmonyOS port of MiniCPM-V end-side demo.
//
// All NAPI function bodies live in llama_napi.cpp; this file only stitches
// them into a single ohos napi module. The module name MUST match the
// dependency in oh-package.json5 ("libentry.so") with both the "lib" prefix
// and ".so" suffix stripped, otherwise `import nativeModule from 'libentry.so'`
// fails at runtime.

#include "napi/native_api.h"

extern napi_value Init(napi_env env, napi_callback_info info);
extern napi_value Load(napi_env env, napi_callback_info info);
extern napi_value LoadMmproj(napi_env env, napi_callback_info info);
extern napi_value GetMinicpmvVersion(napi_env env, napi_callback_info info);
extern napi_value SetMinicpmvVersion(napi_env env, napi_callback_info info);
extern napi_value SetImageMaxSliceNums(napi_env env, napi_callback_info info);
extern napi_value Prepare(napi_env env, napi_callback_info info);
extern napi_value SystemInfo(napi_env env, napi_callback_info info);
extern napi_value ProcessSystemPrompt(napi_env env, napi_callback_info info);
extern napi_value StreamUserPrompt(napi_env env, napi_callback_info info);
extern napi_value PrefillImage(napi_env env, napi_callback_info info);
extern napi_value FullReset(napi_env env, napi_callback_info info);
extern napi_value CancelGeneration(napi_env env, napi_callback_info info);
extern napi_value Unload(napi_env env, napi_callback_info info);
extern napi_value Shutdown(napi_env env, napi_callback_info info);

EXTERN_C_START
static napi_value RegisterModule(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        { "init",                nullptr, Init,                nullptr, nullptr, nullptr, napi_default, nullptr },
        { "load",                nullptr, Load,                nullptr, nullptr, nullptr, napi_default, nullptr },
        { "loadMmproj",          nullptr, LoadMmproj,          nullptr, nullptr, nullptr, napi_default, nullptr },
        { "getMinicpmvVersion",  nullptr, GetMinicpmvVersion,  nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setMinicpmvVersion",  nullptr, SetMinicpmvVersion,  nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setImageMaxSliceNums",nullptr, SetImageMaxSliceNums,nullptr, nullptr, nullptr, napi_default, nullptr },
        { "prepare",             nullptr, Prepare,             nullptr, nullptr, nullptr, napi_default, nullptr },
        { "systemInfo",          nullptr, SystemInfo,          nullptr, nullptr, nullptr, napi_default, nullptr },
        { "processSystemPrompt", nullptr, ProcessSystemPrompt, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "streamUserPrompt",    nullptr, StreamUserPrompt,    nullptr, nullptr, nullptr, napi_default, nullptr },
        { "prefillImage",        nullptr, PrefillImage,        nullptr, nullptr, nullptr, napi_default, nullptr },
        { "fullReset",           nullptr, FullReset,           nullptr, nullptr, nullptr, napi_default, nullptr },
        { "cancelGeneration",    nullptr, CancelGeneration,    nullptr, nullptr, nullptr, napi_default, nullptr },
        { "unload",              nullptr, Unload,              nullptr, nullptr, nullptr, napi_default, nullptr },
        { "shutdown",            nullptr, Shutdown,            nullptr, nullptr, nullptr, napi_default, nullptr },
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}
EXTERN_C_END

static napi_module entryModule = {
    .nm_version       = 1,
    .nm_flags         = 0,
    .nm_filename      = nullptr,
    .nm_register_func = RegisterModule,
    .nm_modname       = "entry",
    .nm_priv          = ((void *) 0),
    .reserved         = { 0 },
};

extern "C" __attribute__((constructor)) void RegisterEntryModule(void) {
    napi_module_register(&entryModule);
}
