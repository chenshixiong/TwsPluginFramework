package com.tws.plugin.core.proxy.systemservice;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import qrom.component.log.QRomLog;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.IBinder;

import com.tws.plugin.bridge.TwsPluginBridgeProvider;
import com.tws.plugin.content.DisplayItem;
import com.tws.plugin.content.PluginDescriptor;
import com.tws.plugin.content.PluginProviderInfo;
import com.tws.plugin.core.PluginLoader;
import com.tws.plugin.core.PluginShadowService;
import com.tws.plugin.core.android.HackActivityManager;
import com.tws.plugin.core.android.HackActivityManagerNative;
import com.tws.plugin.core.android.HackActivityThread;
import com.tws.plugin.core.android.HackContentProviderHolder;
import com.tws.plugin.core.android.HackSingleton;
import com.tws.plugin.core.proxy.MethodDelegate;
import com.tws.plugin.core.proxy.MethodProxy;
import com.tws.plugin.core.proxy.ProxyUtil;
import com.tws.plugin.manager.PluginManagerHelper;
import com.tws.plugin.util.PendingIntentHelper;
import com.tws.plugin.util.ProcessUtil;
import com.tws.plugin.util.ResourceUtil;

/**
 * @author yongchen
 */
public class AndroidAppIActivityManager extends MethodProxy {

    private static final String TAG = "rick_Print:AndroidAppIActivityManager";

    static {
        sMethods.put("getRunningAppProcesses", new getRunningAppProcesses());
        sMethods.put("getIntentSender", new getIntentSender());
        sMethods.put("overridePendingTransition", new overridePendingTransition());
        sMethods.put("serviceDoneExecuting", new serviceDoneExecuting());
        sMethods.put("getContentProvider", new getContentProvider());
    }

    public static void installProxy() {
        QRomLog.d(TAG, "安装ActivityManagerProxy");
        Object androidAppActivityManagerProxy = HackActivityManagerNative.getDefault();
        Object androidAppIActivityManagerStubProxyProxy = ProxyUtil.createProxy(androidAppActivityManagerProxy,
                new AndroidAppIActivityManager());
        if (Build.VERSION.SDK_INT <= 25) {//Build.VERSION_CODES.N_MR1
            Object singleton = HackActivityManagerNative.getGDefault();
            //如果是IActivityManager
            if (singleton.getClass().isAssignableFrom(androidAppIActivityManagerStubProxyProxy.getClass())) {
                HackActivityManagerNative.setGDefault(androidAppIActivityManagerStubProxyProxy);
            } else {//否则是包装过的单例
                new HackSingleton(singleton).setInstance(androidAppIActivityManagerStubProxyProxy);
            }
        } else {
            //gDefault这个变量在8.0被移到了ActivityManager类里面了
            Object singleton = HackActivityManager.getIActivityManagerSingleton();
            if (singleton != null) {
                new HackSingleton(singleton).setInstance(androidAppIActivityManagerStubProxyProxy);
            } else {
                QRomLog.e(TAG, "Android O singleton == null");
            }
        }
        QRomLog.d(TAG, "安装完成");
    }

    // public List<RunningAppProcessInfo> getRunningAppProcesses()
    public static class getRunningAppProcesses extends MethodDelegate {

        @Override
        public Object afterInvoke(Object target, Method method, Object[] args, Object beforeInvoke, Object invokeResult) {
            if (invokeResult == null) {
                return super.afterInvoke(target, method, args, beforeInvoke, invokeResult);
            }

            QRomLog.w(TAG, "getRunningAppProcesses afterInvoke method:" + method.getName());
            // 由于插件运行在插件进程中，这里需要欺骗插件，让插件的中判断进程的逻辑以为当前是在主进程中运行
            // 但是这会导致插件框架也无法判断当前的进程了，因此框架中判断插件进程的方法一定要在安装ActivityManager代理之前执行并记住状态
            // 同时要保证主进程能正确判断进程。
            // 这里不会导致无限递归，因为ProcessUtil.isPluginProcess方法内部有缓存，再安装ActivityManager代理之前已经执行并缓存了
            if (ProcessUtil.isPluginProcess()) {
                List<ActivityManager.RunningAppProcessInfo> result = (List<ActivityManager.RunningAppProcessInfo>) invokeResult;
                for (ActivityManager.RunningAppProcessInfo appProcess : result) {
                    if (appProcess != null && appProcess.pid == android.os.Process.myPid()) {
                        appProcess.processName = PluginLoader.getApplication().getPackageName();
                        break;
                    }
                }
            }

            return super.afterInvoke(target, method, args, beforeInvoke, invokeResult);
        }
    }

    public static class getIntentSender extends MethodDelegate {

        public static final int INTENT_SENDER_BROADCAST = 1;
        public static final int INTENT_SENDER_ACTIVITY = 2;
        public static final int INTENT_SENDER_ACTIVITY_RESULT = 3;
        public static final int INTENT_SENDER_SERVICE = 4;

        @Override
        public Object beforeInvoke(Object target, Method method, Object[] args) {
            QRomLog.w(TAG, "getIntentSender beforeInvoke method=" + method.getName() + " args[1]=" + args[1]);
            int type = (Integer) args[0];
            args[1] = PluginLoader.getApplication().getPackageName();
            if (type != INTENT_SENDER_ACTIVITY_RESULT) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] != null && args[i].getClass().isAssignableFrom(Intent[].class)) {
                        Intent[] intents = (Intent[]) args[i];
                        if (type == INTENT_SENDER_BROADCAST) {
                            type = DisplayItem.TYPE_BROADCAST;
                        } else if (type == INTENT_SENDER_ACTIVITY) {
                            type = DisplayItem.TYPE_ACTIVITY;
                        } else if (type == INTENT_SENDER_SERVICE) {
                            type = DisplayItem.TYPE_SERVICE;
                        }

                        for (int j = 0; j < intents.length; j++) {
                            intents[j] = PendingIntentHelper.resolvePendingIntent(intents[j], type);
                        }
                        break;
                    }
                }
            }

            return super.beforeInvoke(target, method, args);
        }
    }

    public static class overridePendingTransition extends MethodDelegate {
        public Object beforeInvoke(Object target, Method method, Object[] args) {
            if (ProcessUtil.isPluginProcess()) {
                if (!ResourceUtil.isMainResId((Integer) args[2])) {
                    args[2] = 0;
                }
                if (!ResourceUtil.isMainResId((Integer) args[3])) {
                    args[3] = 0;
                }
            }
            return null;
        }
    }

    public static class serviceDoneExecuting extends MethodDelegate {
        public Object beforeInvoke(Object target, Method method, Object[] args) {
            if (ProcessUtil.isPluginProcess()) {
                if (((Integer) args[1]).equals(HackActivityThread.getSERVICE_DONE_EXECUTING_ANON())) {
                    for (Object obj : args) {
                        if (obj instanceof IBinder) {
                            Map<IBinder, Service> services = HackActivityThread.get().getServices();
                            Service service = services.get(obj);
                            if (service instanceof PluginShadowService) {
                                if (((PluginShadowService) service).realService != null) {
                                    services.put((IBinder) obj, ((PluginShadowService) service).realService);
                                } else {
                                    throw new IllegalStateException("unable to create service");
                                }
                            }
                            break;
                        }
                    }
                }
            }
            return null;
        }
    }

    public static class getContentProvider extends MethodDelegate {

        //ApplicationThread, auth, userId, stable
        @Override
        public Object afterInvoke(Object target, Method method, Object[] args, Object beforeInvoke, Object invokeResult) {
            //非插件进程
            if (!ProcessUtil.isPluginProcess()) {
                if (invokeResult == null) {
                    String auth = (String)args[1];
                    Collection<PluginDescriptor> list = PluginManagerHelper.getPlugins();
                    for(PluginDescriptor pluginDescriptor : list) {
                        HashMap<String, PluginProviderInfo> map = pluginDescriptor.getProviderInfos();
                        if (map != null) {
                            Iterator<PluginProviderInfo> iterator = map.values().iterator();
                            while(iterator.hasNext()) {
                                PluginProviderInfo pluginProviderInfo = iterator.next();
                                //在插件中找到了匹配的contentprovider
                                if (auth != null && auth.equals(pluginProviderInfo.getAuthority())) {
                                    //先检查插件是否已经初始化
                                    boolean isrunning = PluginManagerHelper.isRunning(pluginDescriptor.getPackageName());
                                    if (!isrunning) {
                                        isrunning = PluginManagerHelper.wakeup(pluginDescriptor.getPackageName());
                                    }
                                    if (!isrunning) {
                                        return invokeResult;
                                    }

                                    ProviderInfo providerInfo = new ProviderInfo();
                                    providerInfo.applicationInfo = PluginLoader.getApplication().getApplicationInfo();
                                    providerInfo.authority = auth;
                                    providerInfo.name = TwsPluginBridgeProvider.class.getName();
                                    providerInfo.packageName = PluginLoader.getApplication().getPackageName();
                                    Object holder = HackContentProviderHolder.newInstance(providerInfo);

                                    if (holder != null) {
                                        return holder;
                                    } else {
                                        return invokeResult;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return invokeResult;
        }
    }
}
