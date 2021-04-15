package com.kinnara.kecakplugins.pushnotification;

import com.kinnara.kecakplugins.pushnotification.commons.FcmPushNotificationMixin;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.joget.plugin.base.PluginManager;
import org.joget.workflow.model.DefaultParticipantPlugin;
import org.joget.workflow.model.ParticipantPlugin;
import org.joget.workflow.model.WorkflowActivity;
import org.joget.workflow.model.service.WorkflowManager;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class FcmPushNotificationParticipant extends DefaultParticipantPlugin implements FcmPushNotificationMixin {
    private boolean fcmInitialized = false;

    @Override
    public String getName() {
        return getLabel() + getVersion();
    }

    @Override
    public String getVersion() {
        return getClass().getPackage().getImplementationVersion();
    }

    @Override
    public String getDescription() {
        return getClass().getPackage().getImplementationTitle();
    }

    @Override
    public Collection<String> getActivityAssignments(Map properties) {
        ApplicationContext applicationContext = AppUtil.getApplicationContext();
        WorkflowActivity workflowActivity = (WorkflowActivity) properties.get("workflowActivity");
        PluginManager pluginManager = (PluginManager) applicationContext.getBean("pluginManager");
        WorkflowManager workflowManager = (WorkflowManager) applicationContext.getBean("workflowManager");
        ParticipantPlugin participantPlugin = pluginManager.getPluginObject((Map<String, Object>) getProperty("participantPlugin"));

        String notificationTitle = getNotificationTitle(properties);
        String notificationContent = getNotificationContent(properties);
        String authorization = getAuthorization(properties);
        JSONObject jsonPrivateKey = getJsonPrivateKey(properties);

        Collection<String> users = participantPlugin.getActivityAssignments(properties);
        WorkflowActivity activityDefinition = workflowManager.getProcessActivityDefinition(workflowActivity.getProcessDefId(), workflowActivity.getActivityDefId());
        if(WorkflowActivity.TYPE_NORMAL.equals(activityDefinition.getType())) {
            try {
                if (!fcmInitialized) {
                    initializeSdk(jsonPrivateKey);
                    fcmInitialized = true;
                }

                long notificationCount = users.stream()
                        .filter(Objects::nonNull)
                        .filter(u -> !u.isEmpty())
                        .distinct()
                        .peek(u -> LogUtil.info(getClassName(), "Sending notification for process [" + workflowActivity.getProcessId() + "] to user [" + u + "]"))
                        .filter(username -> {
                            try {
                                JSONObject jsonHttpPayload = getNotificationPayload(
                                        null,
                                        username,
                                        workflowActivity,
                                        notificationTitle,
                                        notificationContent);

                                HttpResponse pushNotificationResponse = pushNotification(authorization, jsonHttpPayload, true);

                                // return true when status = 200
                                return Optional
                                        .ofNullable(pushNotificationResponse)
                                        .map(HttpResponse::getStatusLine)
                                        .map(StatusLine::getStatusCode)
                                        .orElse(0) == 200;
                            } catch (IOException | JSONException e) {
                                LogUtil.error(getClassName(), e, e.getMessage());
                                return false;
                            }
                        })
                        .peek(u -> LogUtil.info(getClassName(), "Notification for process [" + activityDefinition.getProcessId() + "] to user [" + u + "] has been sent"))
                        .count();

                if (notificationCount == 0) {
                    LogUtil.warn(getClassName(), "No notification has been sent");
                }
            } catch (IndexOutOfBoundsException | IOException e) {
                LogUtil.error(getClassName(), e, e.getMessage());
            }
        }

        return users;
    }

    @Override
    public String getLabel() {
        return "FCM Push Notification Participant";
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClassName(), "/properties/FcmPushNotificationParticipant.json", null, false, "/message/inboxNotificationTool");
    }

    protected String getNotificationTitle(Map properties) {
        WorkflowActivity workflowActivity = (WorkflowActivity) properties.get("workflowActivity");
        return interpolateVariables(workflowActivity, properties.getOrDefault("notificationTitle", "").toString());
    }

    protected String getNotificationContent(Map properties) {
        WorkflowActivity workflowActivity = (WorkflowActivity) properties.get("workflowActivity");
        return interpolateVariables(workflowActivity, properties.getOrDefault("notificationContent", "").toString());
    }

    protected String getAuthorization(Map properties) {
        return properties.getOrDefault("authorization", "").toString();
    }

    protected JSONObject getJsonPrivateKey(Map properties) {
        try {
            return new JSONObject(properties.getOrDefault("jsonPrivateKey", "{}").toString());
        } catch (JSONException e) {
            LogUtil.error(getClassName(), e, e.getMessage());
            return new JSONObject();
        }
    }
}
