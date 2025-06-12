package com.kinnarastudio.kecakplugins.pushnotification;

import org.joget.apps.app.service.AppUtil;
import org.joget.plugin.base.DefaultApplicationPlugin;

import java.util.Map;

public class FcmSimplePushNotificationTool extends DefaultApplicationPlugin {
    public final static String LABEL = "FCM Simple Push Notification Tool";

    @Override
    public String getName() {
        return "";
    }

    @Override
    public String getVersion() {
        return "";
    }

    @Override
    public String getDescription() {
        return "";
    }

    @Override
    public Object execute(Map map) {
        return null;
    }

    @Override
    public String getLabel() {
        return "";
    }

    @Override
    public String getClassName() {
        return "";
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClassName(), "/properties/FcmSimplePushNotificationTool.json", new String[]{getClassName(), getClassName(), getClassName()}, true, "/messages/InboxNotificationTool");
    }
}
