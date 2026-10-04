package cn.codex.lsposed.cmccnoupdate;

import android.app.Dialog;
import android.app.DownloadManager;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class CmccNoUpdateModule extends XposedModule {
    private static final String TARGET_PACKAGE = "com.greenpoint.android.mc10086.activity";

    private static final byte[] FAKE_CLIENT_UPDATE_BYTES = ("{\n"
            + "\"code\":\"000000\",\n"
            + "\"msg\":\"success\",\n"
            + "\"data\":{\n"
            + "\"needUpdate\":false,\n"
            + "\"version\":\"12.0.9\",\n"
            + "\"latestVersion\":\"12.0.9\",\n"
            + "\"downloadUrl\":\"\",\n"
            + "\"forceUpdate\":false,\n"
            + "\"popupShow\":false,\n"
            + "\"upgradeType\":0,\n"
            + "\"popups\":[],\n"
            + "\"upgradeInfo\":{},\n"
            + "\"versionList\":[]\n"
            + "}\n"
            + "}").getBytes(StandardCharsets.UTF_8);

    private static final byte[] FAKE_HOT_UPDATE_BYTES = ("{\n"
            + "\"code\":\"000000\",\n"
            + "\"msg\":\"success\",\n"
            + "\"data\":{\n"
            + "\"list\":[],\n"
            + "\"hotUpdateList\":[],\n"
            + "\"hotupdateList\":[],\n"
            + "\"versionList\":[],\n"
            + "\"popups\":[],\n"
            + "\"needUpdate\":false,\n"
            + "\"forceUpdate\":false,\n"
            + "\"popupShow\":false,\n"
            + "\"downloadUrl\":\"\"\n"
            + "}\n"
            + "}").getBytes(StandardCharsets.UTF_8);

    private static final Set<String> INSTALLED = new HashSet<>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        logLine("onModuleLoaded");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        logLine("onPackageLoaded package=" + param.getPackageName()
                + " first=" + param.isFirstPackage()
                + " loader=" + describe(param.getDefaultClassLoader()));
        if (TARGET_PACKAGE.equals(param.getPackageName())) {
            installAllHooks(param.getDefaultClassLoader(), "PackageLoaded");
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        logLine("onPackageReady loader=" + describe(param.getClassLoader()));
    }

    private void installAllHooks(ClassLoader loader, String stage) {
        if (loader == null) {
            logLine("skip install hooks: null loader stage=" + stage);
            return;
        }
        logLine("installAllHooks stage=" + stage + " loader=" + describe(loader));
        hookUrlOpenConnection();
        hookHttpUrlConnectionClass(HttpURLConnection.class, "java.net.HttpURLConnection");
        hookAndroidHuc(loader);
        hookOkHttp(loader);
        hookTextViewSetText();
        hookDownloadManager();
    }

    private void hookUrlOpenConnection() {
        once("URL.openConnection", new Runnable() {
            @Override
            public void run() {
                try {
                    hookUrlOpenConnectionMethod(URL.class.getDeclaredMethod("openConnection"));
                    hookUrlOpenConnectionMethod(URL.class.getDeclaredMethod("openConnection", java.net.Proxy.class));
                    logLine("hooked URL.openConnection");
                } catch (Throwable t) {
                    logLine("hook URL.openConnection failed: " + t);
                }
            }
        });
    }

    private void hookUrlOpenConnectionMethod(Method method) {
        hook(method).intercept(new Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                String url = self == null ? "" : String.valueOf(self);
                if (isTargetUrl(url)) {
                    logLine("URL.openConnection target=" + url);
                }
                return result;
            }
        });
    }

    private void hookAndroidHuc(ClassLoader loader) {
        String[] names = new String[]{
                "com.android.okhttp.internal.huc.HttpURLConnectionImpl",
                "com.android.okhttp.internal.huc.HttpsURLConnectionImpl",
                "com.android.okhttp.internal.huc.DelegatingHttpsURLConnection",
                "com.android.okhttp.internal.huc.JavaApiConverter$CacheHttpURLConnection"
        };
        for (String name : names) {
            try {
                hookHttpUrlConnectionClass(Class.forName(name, false, loader), name);
            } catch (Throwable t) {
                logLine("HUC class unavailable " + name + ": " + t.getClass().getSimpleName());
            }
        }
    }

    private void hookHttpUrlConnectionClass(final Class<?> cls, final String key) {
        once("HUC:" + key, new Runnable() {
            @Override
            public void run() {
                hookNoArg(cls, "getResponseCode", new Replacement() {
                    @Override
                    public Object replace(Object self, Object original) {
                        if (isTargetConnection(self)) {
                            logLine("HUC getResponseCode target=" + connectionUrl(self));
                            return Integer.valueOf(200);
                        }
                        return original;
                    }
                });
                hookNoArg(cls, "getResponseMessage", new Replacement() {
                    @Override
                    public Object replace(Object self, Object original) {
                        return isTargetConnection(self) ? "OK" : original;
                    }
                });
                hookNoArg(cls, "getInputStream", new Replacement() {
                    @Override
                    public Object replace(Object self, Object original) {
                        byte[] body = fakeBodyForUrl(connectionUrl(self));
                        if (body != null) {
                            logLine("HUC fake body target=" + connectionUrl(self) + " bytes=" + body.length);
                            return new ByteArrayInputStream(body);
                        }
                        return original;
                    }
                });
                hookNoArg(cls, "getErrorStream", new Replacement() {
                    @Override
                    public Object replace(Object self, Object original) {
                        return isTargetConnection(self) ? null : original;
                    }
                });
                hookNoArg(cls, "getContentLength", new Replacement() {
                    @Override
                    public Object replace(Object self, Object original) {
                        byte[] body = fakeBodyForUrl(connectionUrl(self));
                        return body == null ? original : Integer.valueOf(body.length);
                    }
                });
                hookNoArg(cls, "getContentType", new Replacement() {
                    @Override
                    public Object replace(Object self, Object original) {
                        return isTargetConnection(self) ? "application/json; charset=utf-8" : original;
                    }
                });
                hookHeaderField(cls);
                logLine("hooked HUC class " + key);
            }
        });
    }

    private void hookNoArg(Class<?> cls, String methodName, final Replacement replacement) {
        try {
            Method method = findNoArgMethod(cls, methodName);
            if (method == null) {
                logLine("missing method " + cls.getName() + "." + methodName);
                return;
            }
            method.setAccessible(true);
            hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object self = chain.getThisObject();
                    Object original = chain.proceed();
                    return replacement.replace(self, original);
                }
            });
        } catch (Throwable t) {
            logLine("hookNoArg failed " + cls.getName() + "." + methodName + ": " + t);
        }
    }

    private void hookHeaderField(Class<?> cls) {
        try {
            Method method = cls.getMethod("getHeaderField", String.class);
            hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object self = chain.getThisObject();
                    String name = (String) chain.getArg(0);
                    Object original = chain.proceed();
                    byte[] body = fakeBodyForUrl(connectionUrl(self));
                    if (body == null || name == null) {
                        return original;
                    }
                    String lower = name.toLowerCase(Locale.US);
                    if ("content-type".equals(lower)) {
                        return "application/json; charset=utf-8";
                    }
                    if ("content-length".equals(lower)) {
                        return String.valueOf(body.length);
                    }
                    return original;
                }
            });
        } catch (Throwable t) {
            logLine("hookHeaderField failed " + cls.getName() + ": " + t);
        }
    }

    private void hookOkHttp(final ClassLoader loader) {
        once("OkHttpRealCall:" + describe(loader), new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> realCallClass = Class.forName("okhttp3.RealCall", false, loader);
                    Method execute = realCallClass.getDeclaredMethod("execute");
                    hook(execute).intercept(new Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object response = chain.proceed();
                            return isTargetResponse(response) ? buildFakeOkHttpResponse(response) : response;
                        }
                    });
                    Class<?> callbackClass = Class.forName("okhttp3.Callback", false, loader);
                    Method enqueue = realCallClass.getDeclaredMethod("enqueue", callbackClass);
                    hook(enqueue).intercept(new Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object callback = chain.getArg(0);
                            Object wrapped = wrapCallback(callback, callbackClass, loader);
                            return chain.proceed(new Object[]{wrapped});
                        }
                    });
                    logLine("hooked okhttp3.RealCall");
                } catch (Throwable t) {
                    logLine("hookOkHttp unavailable: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        });
    }

    private Object wrapCallback(final Object callback, Class<?> callbackClass, ClassLoader loader) {
        if (callback == null) {
            return null;
        }
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("onResponse".equals(method.getName()) && args != null && args.length == 2
                        && isTargetResponse(args[1])) {
                    args[1] = buildFakeOkHttpResponse(args[1]);
                }
                try {
                    return method.invoke(callback, args);
                } catch (Throwable t) {
                    Throwable cause = t.getCause();
                    throw cause != null ? cause : t;
                }
            }
        };
        return Proxy.newProxyInstance(loader, new Class<?>[]{callbackClass}, handler);
    }

    private Object buildFakeOkHttpResponse(Object originalResponse) throws Exception {
        String url = okHttpResponseUrl(originalResponse);
        byte[] bytes = fakeBodyForUrl(url);
        if (bytes == null) {
            return originalResponse;
        }
        logLine("OkHttp fake body target=" + url + " bytes=" + bytes.length);
        ClassLoader loader = originalResponse.getClass().getClassLoader();
        Class<?> responseBodyClass = Class.forName("okhttp3.ResponseBody", false, loader);
        Class<?> mediaTypeClass = Class.forName("okhttp3.MediaType", false, loader);
        Object mediaType = mediaTypeClass.getMethod("parse", String.class)
                .invoke(null, "application/json; charset=utf-8");
        Object responseBody = responseBodyClass
                .getMethod("create", mediaTypeClass, String.class)
                .invoke(null, mediaType, new String(bytes, StandardCharsets.UTF_8));
        Object builder = originalResponse.getClass().getMethod("newBuilder").invoke(originalResponse);
        Class<?> builderClass = builder.getClass();
        builderClass.getMethod("code", int.class).invoke(builder, 200);
        builderClass.getMethod("message", String.class).invoke(builder, "OK");
        builderClass.getMethod("body", responseBodyClass).invoke(builder, responseBody);
        return builderClass.getMethod("build").invoke(builder);
    }

    private void hookClassLoaderProbe() {
        once("ClassLoader.loadClass.probe", new Runnable() {
            @Override
            public void run() {
                try {
                    Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
                    hook(loadClass).intercept(new Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            String name = (String) chain.getArg(0);
                            Object result = chain.proceed();
                            if ("okhttp3.RealCall".equals(name) || "okhttp3.ResponseBody".equals(name)
                                    || "com.ijm.dataencryption.DETool".equals(name)) {
                                logLine("ClassLoader loaded " + name + " via " + describe(chain.getThisObject()));
                            }
                            if ("okhttp3.RealCall".equals(name)) {
                                hookOkHttp((ClassLoader) chain.getThisObject());
                            }
                            return result;
                        }
                    });
                    logLine("hooked ClassLoader.loadClass probe");
                } catch (Throwable t) {
                    logLine("hook ClassLoader.loadClass failed: " + t);
                }
            }
        });
    }

    private void hookJsonObject() {
        once("JSONObject.update.fields", new Runnable() {
            @Override
            public void run() {
                hookJsonBool("getBoolean");
                hookJsonBool("optBoolean");
                hookJsonBoolDefault();
                hookJsonInt("getInt");
                hookJsonInt("optInt");
                hookJsonIntDefault();
                hookJsonString("getString");
                hookJsonString("optString");
                hookJsonStringDefault();
                hookJsonArray("getJSONArray");
                hookJsonArray("optJSONArray");
                hookJsonObjectValue("getJSONObject");
                hookJsonObjectValue("optJSONObject");
                logLine("hooked JSONObject upgrade fields");
            }
        });
    }

    private void hookJsonBool(String methodName) {
        try {
            Method method = JSONObject.class.getMethod(methodName, String.class);
            hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    String key = String.valueOf(chain.getArg(0));
                    if (isFalseUpdateBoolKey(key)) {
                        logLine("JSONObject " + key + " => false");
                        return Boolean.FALSE;
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            logLine("hookJsonBool failed " + methodName + ": " + t);
        }
    }

    private void hookJsonBoolDefault() {
        try {
            Method method = JSONObject.class.getMethod("optBoolean", String.class, boolean.class);
            hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    String key = String.valueOf(chain.getArg(0));
                    if (isFalseUpdateBoolKey(key)) {
                        logLine("JSONObject " + key + " => false");
                        return Boolean.FALSE;
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            logLine("hookJsonBoolDefault failed: " + t);
        }
    }

    private void hookJsonInt(String methodName) {
        try {
            Method method = JSONObject.class.getMethod(methodName, String.class);
            hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    String key = String.valueOf(chain.getArg(0));
                    if (isZeroUpdateIntKey(key)) {
                        logLine("JSONObject " + key + " => 0");
                        return Integer.valueOf(0);
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            logLine("hookJsonInt failed " + methodName + ": " + t);
        }
    }

    private void hookJsonIntDefault() {
        try {
            Method method = JSONObject.class.getMethod("optInt", String.class, int.class);
            hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    String key = String.valueOf(chain.getArg(0));
                    if (isZeroUpdateIntKey(key)) {
                        logLine("JSONObject " + key + " => 0");
                        return Integer.valueOf(0);
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            logLine("hookJsonIntDefault failed: " + t);
        }
    }

    private void hookJsonString(String methodName) {
        try {
            Method method = JSONObject.class.getMethod(methodName, String.class);
            hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    String key = String.valueOf(chain.getArg(0));
                    String replacement = replacementStringForKey(key);
                    if (replacement != null) {
                        logLine("JSONObject " + key + " => " + replacement);
                        return replacement;
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            logLine("hookJsonString failed " + methodName + ": " + t);
        }
    }

    private void hookJsonStringDefault() {
        try {
            Method method = JSONObject.class.getMethod("optString", String.class, String.class);
            hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    String key = String.valueOf(chain.getArg(0));
                    String replacement = replacementStringForKey(key);
                    if (replacement != null) {
                        logLine("JSONObject " + key + " => " + replacement);
                        return replacement;
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            logLine("hookJsonStringDefault failed: " + t);
        }
    }

    private void hookJsonArray(String methodName) {
        try {
            Method method = JSONObject.class.getMethod(methodName, String.class);
            hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    String key = String.valueOf(chain.getArg(0));
                    if (isEmptyArrayKey(key)) {
                        logLine("JSONObject " + key + " => []");
                        return new JSONArray();
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            logLine("hookJsonArray failed " + methodName + ": " + t);
        }
    }

    private void hookJsonObjectValue(String methodName) {
        try {
            Method method = JSONObject.class.getMethod(methodName, String.class);
            hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    String key = String.valueOf(chain.getArg(0));
                    if (isEmptyObjectKey(key)) {
                        logLine("JSONObject " + key + " => {}");
                        return new JSONObject();
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            logLine("hookJsonObjectValue failed " + methodName + ": " + t);
        }
    }

    private void hookDialogShow() {
        once("Dialog.show", new Runnable() {
            @Override
            public void run() {
                try {
                    Method method = Dialog.class.getMethod("show");
                    hook(method).intercept(new Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Dialog dialog = (Dialog) chain.getThisObject();
                            if (dialog != null && dialog.getWindow() != null
                                    && containsUpdateText(dialog.getWindow().getDecorView())) {
                                logLine("blocked Dialog.show before display");
                                neutralizeUpdatePopup(dialog.getWindow().getDecorView(), "Dialog.show.pre");
                                return null;
                            }
                            Object result = chain.proceed();
                            if (dialog != null && containsUpdateText(dialog.getWindow() == null
                                    ? null : dialog.getWindow().getDecorView())) {
                                logLine("Dialog.show dismissed update dialog");
                                try {
                                    dialog.dismiss();
                                } catch (Throwable ignored) {
                                }
                            }
                            return result;
                        }
                    });
                    logLine("hooked Dialog.show");
                } catch (Throwable t) {
                    logLine("hook Dialog.show failed: " + t);
                }
            }
        });
    }

    private void hookDialogSetContentView() {
        once("Dialog.setContentView", new Runnable() {
            @Override
            public void run() {
                try {
                    Method[] methods = Dialog.class.getMethods();
                    for (final Method method : methods) {
                        if (!"setContentView".equals(method.getName())) {
                            continue;
                        }
                        Class<?>[] params = method.getParameterTypes();
                        if (params.length == 0 || !View.class.isAssignableFrom(params[0])) {
                            continue;
                        }
                        hook(method).intercept(new Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                View content = (View) chain.getArg(0);
                                if (containsUpdateText(content)) {
                                    logLine("pre-hide Dialog.setContentView update content=" + describeView(content));
                                    neutralizeUpdatePopup(content, "Dialog.setContentView.pre");
                                }
                                Object result = chain.proceed();
                                Dialog dialog = (Dialog) chain.getThisObject();
                                View decor = dialog == null || dialog.getWindow() == null
                                        ? null : dialog.getWindow().getDecorView();
                                if (containsUpdateText(decor)) {
                                    logLine("hidden Dialog.setContentView decor before show");
                                    neutralizeUpdatePopup(decor, "Dialog.setContentView.post");
                                }
                                return result;
                            }
                        });
                        logLine("hooked Dialog." + method);
                    }
                } catch (Throwable t) {
                    logLine("hook Dialog.setContentView failed: " + t);
                }
            }
        });
    }

    private void hookPopupWindow() {
        once("PopupWindow.show", new Runnable() {
            @Override
            public void run() {
                try {
                    Method[] methods = PopupWindow.class.getMethods();
                    for (final Method method : methods) {
                        String name = method.getName();
                        if (!"showAtLocation".equals(name) && !"showAsDropDown".equals(name)) {
                            continue;
                        }
                        hook(method).intercept(new Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                PopupWindow popup = (PopupWindow) chain.getThisObject();
                                View content = popup == null ? null : popup.getContentView();
                                if (containsUpdateText(content)) {
                                    logLine("blocked PopupWindow." + method.getName()
                                            + " update content=" + describeView(content));
                                    neutralizeUpdatePopup(content, "PopupWindow." + method.getName());
                                    try {
                                        popup.dismiss();
                                    } catch (Throwable ignored) {
                                    }
                                    return null;
                                }
                                return chain.proceed();
                            }
                        });
                        logLine("hooked PopupWindow." + method);
                    }
                } catch (Throwable t) {
                    logLine("hook PopupWindow.show failed: " + t);
                }
            }
        });
    }

    private void hookViewGroupAddView() {
        once("ViewGroup.addView", new Runnable() {
            @Override
            public void run() {
                try {
                    Method[] methods = ViewGroup.class.getDeclaredMethods();
                    for (final Method method : methods) {
                        if (!"addView".equals(method.getName())) {
                            continue;
                        }
                        Class<?>[] params = method.getParameterTypes();
                        if (params.length == 0 || !View.class.isAssignableFrom(params[0])) {
                            continue;
                        }
                        method.setAccessible(true);
                        hook(method).intercept(new Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                View child = (View) chain.getArg(0);
                                if (containsUpdateText(child)) {
                                    logLine("blocked ViewGroup.addView update child=" + describeView(child));
                                    neutralizeUpdatePopup((View) chain.getThisObject(), "ViewGroup.addView.pre");
                                    return null;
                                }
                                Object result = chain.proceed();
                                View parent = (View) chain.getThisObject();
                                if (containsUpdateText(parent)) {
                                    neutralizeUpdatePopup(parent, "ViewGroup.addView.post");
                                }
                                return result;
                            }
                        });
                        logLine("hooked ViewGroup." + method);
                    }
                } catch (Throwable t) {
                    logLine("hook ViewGroup.addView failed: " + t);
                }
            }
        });
    }

    private void hookWindowAddView() {
        once("Window.addView", new Runnable() {
            @Override
            public void run() {
                hookAddViewClass("android.view.WindowManagerImpl");
                hookAddViewClass("android.view.WindowManagerGlobal");
            }
        });
    }

    private void hookAddViewClass(String className) {
        try {
            Class<?> cls = Class.forName(className);
            Method[] methods = cls.getDeclaredMethods();
            for (final Method method : methods) {
                if (!"addView".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 0 || !View.class.isAssignableFrom(params[0])) {
                    continue;
                }
                method.setAccessible(true);
                hook(method).intercept(new Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        View view = (View) chain.getArg(0);
                        if (containsUpdateText(view)) {
                            logLine("blocked Window addView update popup via " + method);
                            return null;
                        }
                        return chain.proceed();
                    }
                });
                logLine("hooked " + className + "." + method);
            }
        } catch (Throwable t) {
            logLine("hookAddViewClass failed " + className + ": " + t);
        }
    }

    private void hookTextViewSetText() {
        once("TextView.setText", new Runnable() {
            @Override
            public void run() {
                hookTextSetMethod("setText", CharSequence.class);
                hookTextSetMethod("setText", CharSequence.class, TextView.BufferType.class);
            }
        });
    }

    private void hookTextSetMethod(String name, Class<?>... args) {
        try {
            Method method = TextView.class.getMethod(name, args);
            hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object value = chain.getArg(0);
                    if (value instanceof CharSequence && isUpdateText(String.valueOf(value))) {
                        logLine("TextView update text replaced: " + value);
                        final TextView view = (TextView) chain.getThisObject();
                        preHideUpdateTextView(view, String.valueOf(value));
                        Object[] newArgs = chain.getArgs().toArray(new Object[0]);
                        newArgs[0] = safeReplacementText(String.valueOf(value));
                        Object result = chain.proceed(newArgs);
                        neutralizeUpdatePopupAsync(view, "TextView.setText:" + value);
                        return result;
                    }
                    return chain.proceed();
                }
            });
            logLine("hooked TextView." + method);
        } catch (Throwable t) {
            logLine("hookTextSetMethod failed: " + t);
        }
    }

    private void preHideUpdateTextView(TextView view, String text) {
        if (view == null) {
            return;
        }
        try {
            view.setAlpha(0f);
            view.setVisibility(View.INVISIBLE);
            hideAncestorChain(view, "TextView.setText.pre");
            View root = findLikelyPopupRoot(view);
            if (root != null) {
                root.setAlpha(0f);
                root.setVisibility(View.INVISIBLE);
                disableTree(root);
            }
            logLine("pre-hide update text view=" + describeView(view) + " text=" + text);
        } catch (Throwable t) {
            logLine("preHideUpdateTextView failed: " + t);
        }
    }

    private void hideAncestorChain(View view, String reason) {
        if (view == null) {
            return;
        }
        try {
            View current = view;
            View last = view;
            for (int depth = 0; depth < 12 && current != null; depth++) {
                current.setAlpha(0f);
                current.setVisibility(View.INVISIBLE);
                current.setClickable(false);
                current.setEnabled(false);
                last = current;
                Object parent = current.getParent();
                if (!(parent instanceof View)) {
                    break;
                }
                current = (View) parent;
            }
            logLine("pre-hidden ancestor chain reason=" + reason + " top=" + describeView(last));
        } catch (Throwable t) {
            logLine("hideAncestorChain failed reason=" + reason + ": " + t);
        }
    }

    private String safeReplacementText(String oldText) {
        if (oldText != null && (oldText.contains("发现新版本") || oldText.contains("NEW VERSION"))) {
            return "";
        }
        return "当前版本可继续使用";
    }

    private void hookViewClicks() {
        once("View.clicks", new Runnable() {
            @Override
            public void run() {
                try {
                    Method performClick = View.class.getMethod("performClick");
                    hook(performClick).intercept(new Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            View view = (View) chain.getThisObject();
                            if (isUpdateActionView(view)) {
                                logLine("blocked update performClick view=" + describeView(view));
                                neutralizeUpdatePopupAsync(view, "performClick");
                                return Boolean.FALSE;
                            }
                            return chain.proceed();
                        }
                    });
                    logLine("hooked View.performClick");
                } catch (Throwable t) {
                    logLine("hook View.performClick failed: " + t);
                }

                try {
                    Method setOnClickListener = View.class.getMethod("setOnClickListener", View.OnClickListener.class);
                    hook(setOnClickListener).intercept(new Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            final View view = (View) chain.getThisObject();
                            final View.OnClickListener original = (View.OnClickListener) chain.getArg(0);
                            if (original == null) {
                                return chain.proceed();
                            }
                            View.OnClickListener wrapped = new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    View target = v == null ? view : v;
                                    if (isUpdateActionView(target)) {
                                        logLine("blocked update onClick view=" + describeView(target));
                                        neutralizeUpdatePopupAsync(target, "onClick");
                                        return;
                                    }
                                    original.onClick(v);
                                }
                            };
                            return chain.proceed(new Object[]{wrapped});
                        }
                    });
                    logLine("hooked View.setOnClickListener");
                } catch (Throwable t) {
                    logLine("hook View.setOnClickListener failed: " + t);
                }
            }
        });
    }

    private void hookDownloadManager() {
        once("DownloadManager.enqueue", new Runnable() {
            @Override
            public void run() {
                try {
                    Method enqueue = DownloadManager.class.getMethod("enqueue", DownloadManager.Request.class);
                    hook(enqueue).intercept(new Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object request = chain.getArg(0);
                            String uri = downloadRequestUri(request);
                            if (isUpdateDownloadUri(uri)) {
                                logLine("blocked DownloadManager.enqueue uri=" + uri);
                                return Long.valueOf(-1L);
                            }
                            return chain.proceed();
                        }
                    });
                    logLine("hooked DownloadManager.enqueue");
                } catch (Throwable t) {
                    logLine("hook DownloadManager.enqueue failed: " + t);
                }
            }
        });
    }

    private String downloadRequestUri(Object request) {
        if (request == null) {
            return "";
        }
        try {
            java.lang.reflect.Field field = request.getClass().getDeclaredField("mUri");
            field.setAccessible(true);
            Object value = field.get(request);
            return String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean isUpdateDownloadUri(String uri) {
        if (uri == null) {
            return false;
        }
        String lower = uri.toLowerCase(Locale.US);
        return lower.contains(".apk")
                || lower.contains("12.5.5")
                || lower.contains("upgrade")
                || lower.contains("update")
                || lower.contains("download");
    }

    private boolean isTargetResponse(Object response) {
        return fakeBodyForUrl(okHttpResponseUrl(response)) != null;
    }

    private String okHttpResponseUrl(Object response) {
        try {
            Object request = response.getClass().getMethod("request").invoke(response);
            Object url = request == null ? null : request.getClass().getMethod("url").invoke(request);
            return String.valueOf(url);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean isTargetConnection(Object self) {
        return fakeBodyForUrl(connectionUrl(self)) != null;
    }

    private String connectionUrl(Object self) {
        try {
            if (self instanceof java.net.URLConnection) {
                URL url = ((java.net.URLConnection) self).getURL();
                return String.valueOf(url);
            }
            Method method = self.getClass().getMethod("getURL");
            return String.valueOf(method.invoke(self));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private byte[] fakeBodyForUrl(String url) {
        if (url == null) {
            return null;
        }
        if (url.contains("/biz-orange/DN/clientUpdate/getclientUpdate")
                || url.contains("getclientUpdate")) {
            return FAKE_CLIENT_UPDATE_BYTES;
        }
        if (url.contains("/biz-orange/DN/hotupdate/getHotupdateList")
                || url.contains("getHotupdateList")) {
            return FAKE_HOT_UPDATE_BYTES;
        }
        return null;
    }

    private boolean isTargetUrl(String url) {
        return fakeBodyForUrl(url) != null;
    }

    private boolean isFalseUpdateBoolKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.US);
        return "needupdate".equals(lower)
                || "forceupdate".equals(lower)
                || "popupshow".equals(lower)
                || "isshowupgrade".equals(lower)
                || "showupgrade".equals(lower)
                || "isforce".equals(lower)
                || "forced".equals(lower)
                || "mustupdate".equals(lower);
    }

    private boolean isZeroUpdateIntKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.US);
        return "upgradetype".equals(lower)
                || "popupstype".equals(lower)
                || "updatetype".equals(lower)
                || "forceupdatetype".equals(lower);
    }

    private String replacementStringForKey(String key) {
        if (key == null) {
            return null;
        }
        String lower = key.toLowerCase(Locale.US);
        if ("latestversion".equals(lower) || "newversion".equals(lower)
                || "targetversion".equals(lower) || "upgradeversion".equals(lower)) {
            return "12.0.9";
        }
        if ("downloadurl".equals(lower) || "apkurl".equals(lower)
                || "updateurl".equals(lower) || "url".equals(lower)) {
            return "";
        }
        if ("upgradetitle".equals(lower) || "title".equals(lower)) {
            return "温馨提示";
        }
        if ("upgradeinfo".equals(lower) || "upgradedesc".equals(lower)
                || "description".equals(lower) || "content".equals(lower)) {
            return "";
        }
        return null;
    }

    private boolean isEmptyArrayKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.US);
        return "popups".equals(lower) || "versionlist".equals(lower)
                || "upgradelist".equals(lower) || "updatelist".equals(lower);
    }

    private boolean isEmptyObjectKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.US);
        return "upgradeinfo".equals(lower) || "updateinfo".equals(lower)
                || "popup".equals(lower);
    }

    private boolean containsUpdateText(View view) {
        if (view == null) {
            return false;
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && isUpdateText(String.valueOf(text))) {
                return true;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                if (containsUpdateText(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isUpdateActionView(View view) {
        if (view == null) {
            return false;
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && isUpdateButtonText(String.valueOf(text))) {
                return true;
            }
        }
        return containsUpdateText(view) || containsUpdateText(findLikelyPopupRoot(view));
    }

    private boolean isUpdateButtonText(String text) {
        if (text == null) {
            return false;
        }
        return text.contains("立即体验")
                || text.contains("立即升级")
                || text.contains("立即更新")
                || text.contains("去升级")
                || text.contains("去更新")
                || text.contains("下载更新")
                || text.contains("马上体验")
                || text.contains("马上升级");
    }

    private void neutralizeUpdatePopupAsync(final View view, final String reason) {
        if (view == null) {
            return;
        }
        try {
            view.post(new Runnable() {
                @Override
                public void run() {
                    neutralizeUpdatePopup(view, reason);
                }
            });
        } catch (Throwable ignored) {
            neutralizeUpdatePopup(view, reason);
        }
    }

    private void neutralizeUpdatePopup(View view, String reason) {
        try {
            View target = findLikelyPopupRoot(view);
            if (target == null) {
                target = view;
            }
            disableTree(target);
            target.setVisibility(View.GONE);
            logLine("hidden update popup reason=" + reason + " target=" + describeView(target));
        } catch (Throwable t) {
            logLine("neutralizeUpdatePopup failed reason=" + reason + ": " + t);
        }
    }

    private View findLikelyPopupRoot(View view) {
        if (view == null) {
            return null;
        }
        int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        int screenHeight = Resources.getSystem().getDisplayMetrics().heightPixels;
        View current = view;
        View candidate = view;
        for (int depth = 0; depth < 10 && current != null; depth++) {
            int width = current.getWidth();
            int height = current.getHeight();
            if (width > 0 && height > 0 && width < (int) (screenWidth * 0.98f)) {
                candidate = current;
            }
            Object parent = current.getParent();
            if (!(parent instanceof View)) {
                break;
            }
            current = (View) parent;
        }
        return candidate;
    }

    private void disableTree(View view) {
        if (view == null) {
            return;
        }
        view.setClickable(false);
        view.setEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableTree(group.getChildAt(i));
            }
        }
    }

    private String describeView(View view) {
        if (view == null) {
            return "null";
        }
        String extra = "";
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            extra = " text=" + text;
        }
        return view.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(view))
                + " " + view.getWidth() + "x" + view.getHeight() + extra;
    }

    private boolean isUpdateText(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        return text.contains("12.5.5")
                || text.contains("V12.5.5")
                || text.contains("发现新版本")
                || text.contains("NEW VERSION")
                || text.contains("强制更新")
                || lower.contains("forceupdate")
                || lower.contains("popupshow")
                || lower.contains("needupdate");
    }

    private Method findNoArgMethod(Class<?> cls, String name) {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private void once(String key, Runnable runnable) {
        synchronized (INSTALLED) {
            if (INSTALLED.contains(key)) {
                return;
            }
            INSTALLED.add(key);
        }
        runnable.run();
    }

    private void logLine(String message) {
    }

    private interface Replacement {
        Object replace(Object self, Object original);
    }

    private static String describe(Object object) {
        if (object == null) {
            return "null";
        }
        return object.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(object));
    }
}
