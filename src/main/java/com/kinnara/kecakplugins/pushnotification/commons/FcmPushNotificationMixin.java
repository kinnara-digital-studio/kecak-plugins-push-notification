package com.kinnara.kecakplugins.pushnotification.commons;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
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
import org.joget.commons.util.LogUtil;
import org.joget.workflow.model.WorkflowActivity;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.service.WorkflowManager;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.context.ApplicationContext;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
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

    default void initializeSdk(@Nonnull JSONObject jsonPrivateKey) throws IOException {
        FirebaseOptions options = new FirebaseOptions.Builder()
                .setCredentials(GoogleCredentials.fromStream(new ByteArrayInputStream(jsonPrivateKey.toString().getBytes())))
                .setDatabaseUrl("https://kecak-mobile.firebaseio.com")
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
    default HttpResponse pushNotification(String authorization, JSONObject data, boolean debug) throws IOException {
        if(debug) {
            LogUtil.info(getClass().getName(), "Request Payload ["+data.toString()+"]");
        }

        HttpPost request = new HttpPost(NOTIFICATION_SERVER);
        request.addHeader("Content-Type", CONTENT_TYPE);
        request.addHeader("Authorization", "key=" + authorization);
        request.setEntity(new StringEntity(data.toString()));

        try(CloseableHttpClient client = HttpClientBuilder.create().build();
            CloseableHttpResponse response = client.execute(request)) {

            if (debug) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))) {
                    LogUtil.info(getClass().getName(), "Response Payload [" + br.lines().collect(Collectors.joining()) + "]");
                }
            }

            return response;
        } catch (Exception e) {
            LogUtil.error(getClass().getName(), e, e.getMessage());
        }

        return null;
    }

    default JSONObject getNotificationPayload(String to, String topic, WorkflowAssignment assignment, String notificationTitle, String notificationContent) throws JSONException {
        ApplicationContext applicationContext = AppUtil.getApplicationContext();
        WorkflowManager workflowManager = (WorkflowManager) applicationContext.getBean("workflowManager");
        WorkflowActivity info = workflowManager.getRunningActivityInfo(assignment.getActivityId());

        String formDefId = getFormFromActivity(info.getProcessDefId(), info.getActivityDefId()).orElse("");
        return getNotificationPayload(to, topic, assignment.getProcessId(), info.getProcessName(), assignment.getActivityId(), info.getName(), formDefId, notificationTitle, notificationContent);
    }

    default JSONObject getNotificationPayload(String to, String topic, WorkflowActivity activity, String notificationTitle, String notificationContent) throws JSONException {
        String formDefId = getFormFromActivity(activity.getProcessDefId(), activity.getActivityDefId()).orElse("");
        return getNotificationPayload(to, topic, activity.getProcessId(), activity.getProcessName(), activity.getId(), activity.getName(), formDefId, notificationTitle, notificationContent);
    }

    /**
     *
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

        JSONObject jsonHtmlPayload = new JSONObject();
        if(to != null && !to.isEmpty())
            jsonHtmlPayload.put("to", to);

        if(topic != null && !topic.isEmpty())
            jsonHtmlPayload.put("to", "/topics/" + topic);

        jsonHtmlPayload.put("content_available", true);

        JSONObject jsonData = new JSONObject();
        jsonData.put("title", title);
        jsonData.put("message", content);
        jsonData.put("activityName", activityName);
        jsonData.put("activityId", activityId);
        jsonData.put("processId", processId);
        jsonData.put("processName", processName);
        jsonData.put("appId", appDefinition.getAppId());
        jsonData.put("appVersion", appDefinition.getVersion());
        jsonData.put("formId", formDefId);
        jsonData.put("click_action", "FLUTTER_NOTIFICATION_CLICK");
        jsonHtmlPayload.put("data", jsonData);

        JSONObject jsonNotification = new JSONObject();
        jsonNotification.put("title", title);
        jsonNotification.put("body", content);
        jsonHtmlPayload.put("notification", jsonNotification);

        return jsonHtmlPayload;
    }

    /**
     * Get Form from Activity
     * @param processDefId
     * @param activityDefId
     * @return
     */
    default Optional<String> getFormFromActivity(String processDefId, String activityDefId) {
        if(activityDefId == null)
            return Optional.empty();

        AppDefinition appDefinition = AppUtil.getCurrentAppDefinition();
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        PackageActivityForm packageActivityForm = appService.retrieveMappedForm(appDefinition.getAppId(), String.valueOf(appDefinition.getVersion()), processDefId, activityDefId);
        return Optional.ofNullable(packageActivityForm).map(PackageActivityForm::getFormId);
    }

    default String interpolateVariables(WorkflowActivity activity, String content) {
        WorkflowManager workflowManager = (WorkflowManager) AppUtil.getApplicationContext().getBean("workflowManager");
        String processId = activity.getProcessId();

        StringBuffer sb = new StringBuffer();
        Matcher codeMatcher = CODE_PATTERN.matcher(content);
        while(codeMatcher.find()) {
            String code = codeMatcher.group();

            Matcher wfVariableMatcher = WF_VARIABLE_PATTERN.matcher(code);
            Matcher activityPropertyMatcher = ACTIVITY_PROPERTY_PATTERN.matcher(code);

            if(wfVariableMatcher.find()) {
                String variableName = wfVariableMatcher.group();
                String value = workflowManager.getProcessVariable(processId, variableName);
                codeMatcher.appendReplacement(sb, value);
            } else if(activityPropertyMatcher.find()) {
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
}
