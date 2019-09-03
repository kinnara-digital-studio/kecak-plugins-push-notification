package com.kinnara.plugins.pushnotification;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.Form;
import org.joget.commons.util.LogUtil;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.context.ApplicationContext;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FcmPushNotificationTool extends DefaultApplicationPlugin {
    private final static String NOTIFICATION_SERVER = "https://fcm.googleapis.com/fcm/send";
    private final static String CONTENT_TYPE = "application/json";

    private Map<String, Form> formCache = new HashMap<>();
    private static boolean fcmInitialized = false;

    public String getLabel() {
        return getName();
    }

    public String getClassName() {
        return getClass().getName();
    }

    public String getPropertyOptions() {
        return AppUtil.readPluginResource(this.getClass().getName(), "/properties/inboxNotificationTool.json", null, true, "message/inboxNotificationTool");}

    public String getName() {
        return "FCM Push Notification Tool";
    }

    public String getVersion() {
        return getClass().getPackage().getImplementationVersion();
    }

    public String getDescription() {
        return getClass().getPackage().getImplementationTitle();
    }


    @Override
    public Object execute(Map props) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            if (!fcmInitialized) {
                try {
                    initializeSdk();
                    fcmInitialized = true;
                } catch (IOException e) {
                    LogUtil.error(getClassName(), e, "error initializing firebase");
                }
            }

            WorkflowAssignment activityAssignment = (WorkflowAssignment) props.get("workflowAssignment");
            if (activityAssignment == null) {
                LogUtil.warn(getClassName(), "No assignment found");
                return null;
            }

            LogUtil.info(getClassName(), activityAssignment.getActivityDefId());

            String toParticipantId = getPropertyString("participantId");
            List<String> assignmentUsers = Arrays.stream(toParticipantId.split("[,;]"))
                    .map(String::trim)
                    .filter(p -> !p.isEmpty())
                    .map(p -> WorkflowUtil.getAssignmentUsers(
                            WorkflowUtil.getProcessDefPackageId(activityAssignment.getProcessDefId()),
                            activityAssignment.getProcessDefId(),
                            activityAssignment.getProcessId(),
                            activityAssignment.getProcessVersion(),
                            activityAssignment.getActivityId(),
                            "",
                            p
                    ))
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .distinct()
                    .collect(Collectors.toList());

            if (assignmentUsers.isEmpty()) {
                LogUtil.warn(getClassName(), "No user for assignment [" + activityAssignment.getActivityId() + "] participant [" + toParticipantId + "]");
                return null;
            }

            // push to participants
            assignmentUsers
                    .stream()
                    .filter(Objects::nonNull)
                    .filter(u -> !u.isEmpty())
                    .peek(u -> LogUtil.info(getClassName(), "Sending notification for process [" + activityAssignment.getProcessId() + "] user [" + u + "]"))
                    .forEach(u -> {
                        try {
                            JSONObject jsonHttpPayload = buildHttpBody(
                                    null,
                                    "/topics/" + u,
                                    activityAssignment.getActivityName(),
                                    activityAssignment.getActivityId(),
                                    activityAssignment.getProcessId(),
                                    activityAssignment.getProcessName(),
                                    AppUtil.processHashVariable(getPropertyString("notificationTitle"), activityAssignment, null, null),
                                    AppUtil.processHashVariable(getPropertyString("notificationContent"), activityAssignment, null, null),
                                    activityAssignment);

                            pushNotification(jsonHttpPayload);
                        } catch (IOException | JSONException e) {
                            LogUtil.error(getClassName(), e, e.getMessage());
                        }
                    });

            // push to specific device Id
            Optional.ofNullable(getPropertyString("to"))
                    .map(s -> s.split("[;,]"))
                    .map(Arrays::stream)
                    .orElse(Stream.empty())
                    .filter(s -> !s.isEmpty())
                    .forEach(s -> {
                        try {
                            JSONObject jsonHttpPayload = buildHttpBody(
                                    s,
                                    null,
                                    activityAssignment.getActivityName(),
                                    activityAssignment.getActivityId(),
                                    activityAssignment.getProcessId(),
                                    activityAssignment.getProcessName(),
                                    AppUtil.processHashVariable(getPropertyString("notificationTitle"), activityAssignment, null, null),
                                    AppUtil.processHashVariable(getPropertyString("notificationContent"), activityAssignment, null, null),
                                    activityAssignment);

                            pushNotification(jsonHttpPayload);
                        } catch (IOException | JSONException e) {
                            LogUtil.error(getClassName(), e, e.getMessage());
                        }
                    });
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }

        return null;
    }

    private JSONObject buildHttpBody(String to, String topic, String activityName, String activityId, String processId, String processName, String title, String content, WorkflowAssignment wfAssignment)throws JSONException {
        JSONObject jsonHtmlPayload = new JSONObject();
        if(to != null && !to.isEmpty())
            jsonHtmlPayload.put("to", to);

        if(topic != null && !topic.isEmpty())
            jsonHtmlPayload.put("topic", topic);

        jsonHtmlPayload.put("content_available", true);

        JSONObject jsonData = new JSONObject();
        jsonData.put("title", title);
        jsonData.put("message", content);
        jsonData.put("activityName", activityName);
        jsonData.put("activityId", activityId);
        jsonData.put("processId", processId);
        jsonData.put("processName", processName);
        jsonData.put("click_action", "FLUTTER_NOTIFICATION_CLICK");
        jsonHtmlPayload.put("data", jsonData);

        JSONObject jsonNotification = new JSONObject();
        jsonNotification.put("title", title);
        jsonNotification.put("body", content);
        jsonHtmlPayload.put("notification", jsonNotification);

        return jsonHtmlPayload;
    }

    /**
     * Trigger Notification
     *
     * @param data
     * @return
     * @throws IOException
     */
    private HttpResponse pushNotification(JSONObject data) throws IOException {
        boolean debug = "true".equalsIgnoreCase(getPropertyString("debug"));

        if(debug) {
            LogUtil.info(getClassName(), "Request Payload ["+data.toString()+"]");
        }

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
        try {
            HttpClient client = HttpClientBuilder.create().build();
            HttpPost request = new HttpPost(NOTIFICATION_SERVER);
            request.addHeader("Content-Type", CONTENT_TYPE);
            request.addHeader("Authorization", "key=" + getPropertyString("authorization"));
            request.setEntity(new StringEntity(data.toString()));

            HttpResponse response = client.execute(request);

            if (debug) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))) {
                    LogUtil.info(getClassName(), "Response Payload [" + br.lines().collect(Collectors.joining()) + "]");
                }
            }

            return response;
        } catch (Exception e) {
            LogUtil.error(getClassName(), e, e.getMessage());
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }

        return null;
    }

    private void initializeSdk() throws IOException {
        FirebaseOptions options = new FirebaseOptions.Builder()
                .setCredentials(GoogleCredentials.fromStream(getServiceAccount()))
                .setDatabaseUrl("https://kecak-mobile.firebaseio.com")
                .build();

        FirebaseApp.initializeApp(options);
    }

    private InputStream getServiceAccount() {
        return new ByteArrayInputStream(getPropertyString("jsonPrivateKey").getBytes());
    }
}
