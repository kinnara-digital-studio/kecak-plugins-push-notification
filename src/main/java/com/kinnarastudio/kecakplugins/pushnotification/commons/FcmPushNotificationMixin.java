package com.kinnarastudio.kecakplugins.pushnotification.commons;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.kinnarastudio.commons.Try;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.joget.apps.app.lib.WorkflowAssignmentHashVariable;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.PackageActivityForm;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.Form;
import org.joget.apps.form.model.FormData;
import org.joget.commons.util.LogUtil;
import org.joget.workflow.model.WorkflowActivity;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nonnull;
import java.io.*;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public interface FcmPushNotificationMixin {
    Pattern CODE_PATTERN = Pattern.compile("\\$\\{[a-zA-Z_][a-zA-Z0-9_]+\\.[a-zA-Z_][a-zA-Z0-9_]+\\}");
    Pattern WF_VARIABLE_PATTERN = Pattern.compile("(?<=\\$\\{variable\\.)[a-zA-Z_][a-zA-Z0-9_]+(?=\\})");
    Pattern ACTIVITY_PROPERTY_PATTERN = Pattern.compile("(?<=\\$\\{activity\\.)[a-zA-Z_][a-zA-Z0-9_]+(?=\\})");

    String NOTIFICATION_SERVER = "https://fcm.googleapis.com/fcm/send";
    String CONTENT_TYPE = "application/json";

    default void initializeSdk(@Nonnull String projectId, @Nonnull JSONObject jsonPrivateKey) throws IOException {
        FirebaseOptions options = FirebaseOptions
                .builder()
                .setCredentials(GoogleCredentials.fromStream(new ByteArrayInputStream(jsonPrivateKey.toString().getBytes())))
                .setProjectId(projectId)
                .build();

        FirebaseApp.initializeApp(options);
    }

    /**
     * Trigger Notification
     *
     * @param data
     * @return
     * @throws IOException
     */
    @Deprecated
    default HttpResponse triggerPushNotification(String authorization, JSONObject data, boolean debug) throws IOException {
        if (debug) {
            LogUtil.info(getClass().getName(), "Request Payload [" + data.toString() + "]");
        }

        HttpPost request = getFcmRequest(authorization, data);

        try (CloseableHttpClient client = HttpClientBuilder.create().build();
             CloseableHttpResponse response = client.execute(request)) {

            if (debug) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))) {
                    if (debug) {
                        LogUtil.info(getClass().getName(), "Response Payload [" + br.lines().collect(Collectors.joining()) + "]");
                    }
                }
            }

            return response;
        } catch (Exception e) {
            LogUtil.error(getClass().getName(), e, e.getMessage());
        }

        return null;
    }

    default boolean sendNotification(AppDefinition appDefinition, String username, WorkflowActivity activity, String title, String content) throws IOException, JSONException {
        final Message.Builder messageBuilder = Message.builder()
                .setTopic(username)
                .putData("title", title)
                .putData("messages", content);
        if (activity != null) {
            messageBuilder.putData("activityName", activity.getName());
            messageBuilder.putData("activityId", activity.getId());
            messageBuilder.putData("processName", activity.getProcessName());
            messageBuilder.putData("processId", activity.getProcessId());
        }

        messageBuilder.putData("appId", appDefinition.getAppId());
        messageBuilder.putData("appVersion", appDefinition.getVersion().toString());
        messageBuilder.putData("click_action", "FLUTTER_NOTIFICATION_CLICK");

        final Notification notification = Notification.builder()
                .setTitle(title)
                .setBody(content)
                .build();
        messageBuilder.setNotification(notification);

        try {
            Message message = messageBuilder.build();
            String response = FirebaseMessaging.getInstance().send(message);
            LogUtil.info(getClass().getName(), "Firebase : successfully sent message with response [" + response + "]");
            return true;
        } catch (FirebaseMessagingException e) {
            LogUtil.error(getClass().getName(), e, e.getMessage());
            return false;
        }
    }

    default boolean sendNotification(AppDefinition appDefinition, String username, @Nonnull WorkflowAssignment assignment, String title, String content) throws IOException, JSONException {
        final WorkflowManager workflowManager = (WorkflowManager) AppUtil.getApplicationContext().getBean("workflowManager");
        final WorkflowActivity workflowActivity = workflowManager.getActivityById(assignment.getActivityId());
        return sendNotification(appDefinition, username, workflowActivity, title, content);
    }

    default long sendNotifications(AppDefinition appDefinition, Collection<String> usernames, @Nonnull WorkflowActivity activity, String authorization, String title, String content) {
        return usernames.stream()
                .filter(Objects::nonNull)
                .filter(u -> !u.isEmpty())
                .distinct()
                .filter(Try.onPredicate(username -> sendNotification(appDefinition, username, activity, title, content)))
                .count();
    }

    default long sendNotifications(AppDefinition appDefinition, Collection<String> usernames, String processIdOrActivityId, String title, String content) {
        final WorkflowUserManager workflowUserManager = (WorkflowUserManager) AppUtil.getApplicationContext().getBean("workflowUserManager");
        final WorkflowManager workflowManager = (WorkflowManager) AppUtil.getApplicationContext().getBean("workflowManager");

        return usernames.stream()
                .filter(Objects::nonNull)
                .filter(u -> !u.isEmpty())
                .distinct()
                .filter(Try.onPredicate(username -> {
                    workflowUserManager.setCurrentThreadUser(username);

                    final WorkflowAssignment assignment = Optional.of(processIdOrActivityId)
                            .map(workflowManager::getAssignment)
                            .orElseGet(() -> workflowManager.getAssignmentByProcess(processIdOrActivityId));

                    if (assignment == null) {
                        LogUtil.warn(getClass().getName(), "sendNotification : no assignment found for id [" + processIdOrActivityId + "]");
                        return false;
                    }

                    return sendNotification(appDefinition, username, assignment, title, content);
                }))
                .count();
    }

    default JSONObject getNotificationPayload(AppDefinition appDefinition, String to, String topic, WorkflowActivity activity, String notificationTitle, String notificationContent) throws JSONException {
        String formDefId = getFormFromActivity(appDefinition, activity.getProcessDefId(), activity.getActivityDefId()).map(f -> f.getPropertyString("id")).orElse("");
        return getNotificationPayload(to, topic, activity.getProcessId(), activity.getProcessName(), activity.getId(), activity.getName(), formDefId, notificationTitle, notificationContent);
    }

    /**
     * @param to
     * @param topic
     * @param processId
     * @param processName
     * @param activityId
     * @param activityName
     * @param title
     * @param content
     * @return
     * @throws JSONException
     */
    default JSONObject getNotificationPayload(String to, String topic, String processId, String processName, String activityId, String activityName, String formDefId, String title, String content) throws JSONException {
        AppDefinition appDefinition = AppUtil.getCurrentAppDefinition();
        return new JSONObject() {{
            if (to != null && !to.isEmpty())
                put("to", to);

            if (topic != null && !topic.isEmpty())
                put("to", "/topics/" + topic);

            put("content_available", true);

            put("data", new JSONObject() {{
                put("title", title);
                put("messages", content);
                put("activityName", activityName);
                put("activityId", activityId);
                put("processId", processId);
                put("processName", processName);
                put("appId", appDefinition.getAppId());
                put("appVersion", appDefinition.getVersion());
                put("formId", formDefId);
                put("click_action", "FLUTTER_NOTIFICATION_CLICK");
            }});

            put("notification", new JSONObject() {{
                put("title", title);
                put("body", content);
            }});
        }};
    }

    default Optional<Form> getFormFromAssignment(WorkflowAssignment workflowAssignment) {
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");

        // get application definition
        @Nonnull Optional<AppDefinition> optAppDefinition = getApplicationDefinition(workflowAssignment);

        FormData formData = new FormData();
        formData.setActivityId(workflowAssignment.getActivityId());
        formData.setProcessId(workflowAssignment.getProcessId());

        final Optional<Form> optForm = optAppDefinition.map(def -> appService.viewAssignmentForm(def, workflowAssignment, formData, ""))
                .map(PackageActivityForm::getForm)
                .filter(f -> isAuthorize(f, formData));

        return optForm;
    }

    /**
     * Check form authorization
     * Restrict if no permission is set and user is anonymous
     *
     * @param form
     * @param formData
     * @return
     */
    default boolean isAuthorize(@Nonnull Form form, FormData formData) {
        return (form.getProperty("permission") != null || !WorkflowUtil.isCurrentUserAnonymous())
                && form.isAuthorize(formData);
    }

    /**
     * Attempt to get app definition using activity ID or process ID
     *
     * @param assignment
     * @return
     */
    @Nonnull
    default Optional<AppDefinition> getApplicationDefinition(@Nonnull WorkflowAssignment assignment) {
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");

        final String activityId = assignment.getActivityId();
        final String processId = assignment.getProcessId();

        AppDefinition appDefinition = Optional.of(activityId)
                .map(appService::getAppDefinitionForWorkflowActivity)
                .orElseGet(() -> Optional.of(processId)
                        .map(appService::getAppDefinitionForWorkflowProcess)
                        .orElse(null));

        return Optional.ofNullable(appDefinition);
    }

    /**
     * Get Form from Activity
     *
     * @param processDefId
     * @param activityDefId
     * @return
     */
    default Optional<Form> getFormFromActivity(AppDefinition appDefinition, String processDefId, String activityDefId) {
        if (activityDefId == null) {
            LogUtil.warn(getClass().getName(), "activityDefId is null");
            return Optional.empty();
        }

        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        if (appService == null) {
            LogUtil.warn(getClass().getName(), "AppService is null");
            return Optional.empty();
        }

        if (appDefinition == null) {
            LogUtil.warn(getClass().getName(), "AppDefinition is null");
            return Optional.empty();
        }

        PackageActivityForm packageActivityForm = appService.retrieveMappedForm(appDefinition.getAppId(), String.valueOf(appDefinition.getVersion()), processDefId, activityDefId);
        return Optional.ofNullable(packageActivityForm).map(PackageActivityForm::getForm);
    }

    default String interpolateVariables(WorkflowActivity activity, String content) {
        WorkflowManager workflowManager = (WorkflowManager) AppUtil.getApplicationContext().getBean("workflowManager");
        String processId = activity.getProcessId();

        StringBuffer sb = new StringBuffer();
        Matcher codeMatcher = CODE_PATTERN.matcher(content);
        while (codeMatcher.find()) {
            String code = codeMatcher.group();

            Matcher wfVariableMatcher = WF_VARIABLE_PATTERN.matcher(code);
            Matcher activityPropertyMatcher = ACTIVITY_PROPERTY_PATTERN.matcher(code);

            if (wfVariableMatcher.find()) {
                String variableName = wfVariableMatcher.group();
                String value = workflowManager.getProcessVariable(processId, variableName);
                codeMatcher.appendReplacement(sb, value);
            } else if (activityPropertyMatcher.find()) {
                String propertyName = activityPropertyMatcher.group();
                char firstChar = propertyName.charAt(0);
                firstChar = Character.toUpperCase(firstChar);
                propertyName = firstChar + propertyName.substring(1);

                try {
                    Method method = WorkflowActivity.class.getDeclaredMethod("get" + propertyName, new Class[]{});
                    String value = (String) method.invoke(activity, new Object[]{});
                    if (value != null) {
                        codeMatcher.appendReplacement(sb, value);
                    }
                } catch (Exception e) {
                    LogUtil.error(WorkflowAssignmentHashVariable.class.getName(), e, "Error retrieving activity attribute [" + propertyName + "]");
                }
            }
        }

        codeMatcher.appendTail(sb);
        return sb.toString();
    }

    @Nonnull
    default HttpPost getFcmRequest(String fcmAuthorization, JSONObject fcmPayload) throws UnsupportedEncodingException {
        HttpPost request = new HttpPost(NOTIFICATION_SERVER);
        request.addHeader("Content-Type", CONTENT_TYPE);
        request.addHeader("Authorization", "key=" + fcmAuthorization);
        request.setEntity(new StringEntity(fcmPayload.toString()));
        return request;
    }

    default JSONObject buildHttpBody(final String to, final String topic, final String title, final String content, final WorkflowAssignment wfAssignment) throws JSONException {
        WorkflowManager workflowManager = (WorkflowManager) AppUtil.getApplicationContext().getBean("workflowManager");

        JSONObject jsonHtmlPayload = new JSONObject();
        if (to != null && !to.isEmpty())
            jsonHtmlPayload.put("to", to);

        if (topic != null && !topic.isEmpty())
            jsonHtmlPayload.put("to", "/topics/" + topic);

        jsonHtmlPayload.put("content_available", true);

        JSONObject jsonData = new JSONObject();
        jsonData.put("title", title);
        jsonData.put("messages", content);

        if (wfAssignment != null) {
            WorkflowActivity info = workflowManager.getRunningActivityInfo(wfAssignment.getActivityId());
            if (info != null) {
                jsonData.put("activityDefId", info.getActivityDefId());
                jsonData.put("processDefId", info.getProcessDefId());
            }

            jsonData.put("activityName", wfAssignment.getActivityName());
            jsonData.put("activityId", wfAssignment.getActivityId());
            jsonData.put("processId", wfAssignment.getProcessId());
            jsonData.put("processName", wfAssignment.getProcessName());
        }

        Optional<Form> optForm = getFormFromAssignment(wfAssignment);
        optForm.ifPresent(Try.onConsumer(f -> jsonData.put("formId", f.getPropertyString("id"))));

        jsonData.put("click_action", "FLUTTER_NOTIFICATION_CLICK");
        jsonHtmlPayload.put("data", jsonData);

        JSONObject jsonNotification = new JSONObject();
        jsonNotification.put("title", title);
        jsonNotification.put("body", content);
        jsonHtmlPayload.put("notification", jsonNotification);

        return jsonHtmlPayload;
    }
}
