package com.kinnarastudio.kecakplugins.pushnotification;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.PluginThread;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.plugin.base.PluginManager;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.json.JSONException;
import org.json.JSONObject;

import com.kinnarastudio.commons.Try;
import com.kinnarastudio.kecakplugins.pushnotification.commons.FcmPushNotificationMixin;
import com.kinnarastudio.kecakplugins.pushnotification.commons.NotificationUtil;

public class FcmSimplePushNotificationTool extends DefaultApplicationPlugin implements FcmPushNotificationMixin {

    private static boolean fcmInitialized = false;

    public final static String LABEL = "FCM Simple Push Notification Tool";

    @Override
    public String getName() {
        return LABEL;
    }

    @Override
    public String getVersion() {
        PluginManager pluginManager = (PluginManager) AppUtil.getApplicationContext().getBean("pluginManager");
        ResourceBundle resourceBundle = pluginManager.getPluginMessageBundle(getClassName(), "/messages/BuildNumber");
        String buildNumber = resourceBundle.getString("buildNumber");
        return buildNumber;
    }

    @Override
    public String getDescription() {
        return getClass().getPackage().getImplementationTitle();
    }

    public String getProjectId(Map props) {
        return props.get("fcmDatabaseUrl").toString();
    }

    @Override
    public Object execute(Map map) {
        final PluginManager pluginManager = (PluginManager) map.get("pluginManager");
        final WorkflowManager workflowManager = (WorkflowManager) pluginManager.getBean("workflowManager");
        final WorkflowUserManager workflowUserManager = (WorkflowUserManager) pluginManager.getBean("workflowUserManager");
        final AppDefinition appDefinition = (AppDefinition) map.get("appDef");

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {

            if (!fcmInitialized) {
                String projectId = getProjectId(map);

                LogUtil.info(getClassName(), "Project ID: " + projectId);

                JSONObject jsonPrivateKey = new JSONObject(map.get("jsonPrivateKey").toString());
                initializeSdk(projectId, jsonPrivateKey);
                fcmInitialized = true;
            }

            WorkflowAssignment workflowAssignment = (WorkflowAssignment) map.get("workflowAssignment");
            if (workflowAssignment == null) {
                LogUtil.warn(getClassName(), "No assignment found");
                return null;
            }

            String toParticipantIds = getPropertyString("participantId");
            String[] toUserId = getPropertyString("userId").split("[;,]");

            final String title = map.get("notificationTitle").toString();
            final String content = map.get("notificationContent").toString();
            final String processId = workflowAssignment.getProcessId();

            Set<String> assignmentUsers = Arrays.stream(toParticipantIds.split("[,;]"))
                    .map(String::trim)
                    .filter(p -> !p.isEmpty())
                    .map(participantId -> NotificationUtil.getUsers(participantId, workflowAssignment))
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());

            LogUtil.info(getClassName(), "Assignment Users: " + assignmentUsers);

            new PluginThread(Try.onRunnable(() -> {
                Thread.sleep(3000);

                final Collection<String> users = Stream.concat(assignmentUsers.stream(), Arrays.stream(toUserId))
                .filter(s -> !s.isEmpty())        
                .collect(Collectors.toSet());

                long notificationCount = sendNotifications(appDefinition, users, title, content);

                if (notificationCount == 0) {
                    LogUtil.warn(getClassName(), "Nobody received the notification");
                }

            })).start();
        } catch (JSONException | IOException e) {
            LogUtil.error(getClassName(), e, e.getMessage());
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }

        return null;
    }

    @Override
    public String getLabel() {
        return LABEL;
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClassName(), "/properties/FcmSimplePushNotificationTool.json", new String[]{getClassName(), getClassName(), getClassName()}, true, "/messages/InboxNotificationTool");
    }
}
