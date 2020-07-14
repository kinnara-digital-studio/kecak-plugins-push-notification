package com.kinnara.kecakplugins.pushnotification;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.PackageActivityForm;
import org.joget.apps.app.model.PackageDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.plugin.base.PluginWebSupport;
import org.joget.workflow.model.WorkflowActivity;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.WorkflowParticipant;
import org.joget.workflow.model.WorkflowProcess;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.context.ApplicationContext;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FcmPushNotificationTool extends DefaultApplicationPlugin implements PluginWebSupport {
    private final static String NOTIFICATION_SERVER = "https://fcm.googleapis.com/fcm/send";
    private final static String CONTENT_TYPE = "application/json";

    private static boolean fcmInitialized = false;

    @Override
    public String getLabel() {
        return "FCM Push Notification Tool";
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(this.getClass().getName(), "/properties/inboxNotificationTool.json", new String[] {getClassName(), getClassName(), getClassName()}, true, "message/inboxNotificationTool");}

    @Override
    public String getName() {
        return getClass().getName();
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
            if(activityAssignment == null) {
                LogUtil.warn(getClassName(), "No assignment found");
                return null;
            }

            String toParticipantId = getPropertyString("participantId");
            String[] toUserId = getPropertyString("userId").split("[;,]");

            final String processDefId = getPropertyString("processId");
            final String activityDefId = getPropertyString("activityDefId");

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

            long notificationCount = Stream.concat(assignmentUsers.stream(), Arrays.stream(toUserId))
                    .filter(Objects::nonNull)
                    .filter(u -> !u.isEmpty())
                    .distinct()
                    .peek(u -> LogUtil.info(getClassName(), "Sending notification for process [" + activityAssignment.getProcessId() + "] to user [" + u + "]"))
                    .map(username -> {
                        try {
                            JSONObject jsonHttpPayload = buildHttpBody(
                                    null,
                                    username,
                                    processDefId,
                                    activityDefId,
                                    activityAssignment.getActivityName(),
                                    activityAssignment.getActivityId(),
                                    activityAssignment.getProcessId(),
                                    activityAssignment.getProcessName(),
                                    AppUtil.processHashVariable(getPropertyString("notificationTitle"), activityAssignment, null, null),
                                    AppUtil.processHashVariable(getPropertyString("notificationContent"), activityAssignment, null, null),
                                    activityAssignment);

                            HttpResponse response = pushNotification(jsonHttpPayload);

                            // return true when status = 200
                            return Optional
                                    .ofNullable(response)
                                    .map(HttpResponse::getStatusLine)
                                    .map(StatusLine::getStatusCode)
                                    .orElse(0) == 200;
                        } catch (IOException | JSONException e) {
                            LogUtil.error(getClassName(), e, e.getMessage());
                        }

                        return false;
                    })
                    .filter(b -> b)
                    .count();

            if(notificationCount == 0) {
                LogUtil.warn(getClassName(), "Nobody received tbe notification");
            }

//            // push to specific device Id
//            Optional.ofNullable(getPropertyString("to"))
//                    .map(s -> s.split("[;,]"))
//                    .map(Arrays::stream)
//                    .orElse(Stream.empty())
//                    .filter(s -> !s.isEmpty())
//                    .forEach(s -> {
//                        try {
//                            JSONObject jsonHttpPayload = buildHttpBody(
//                                    s,
//                                    null,
//                                    processDefId,
//                                    activityDefId,
//                                    activityAssignment.getActivityName(),
//                                    activityAssignment.getActivityId(),
//                                    activityAssignment.getProcessId(),
//                                    activityAssignment.getProcessName(),
//                                    AppUtil.processHashVariable(getPropertyString("notificationTitle"), activityAssignment, null, null),
//                                    AppUtil.processHashVariable(getPropertyString("notificationContent"), activityAssignment, null, null),
//                                    activityAssignment);
//
//                            pushNotification(jsonHttpPayload);
//                        } catch (IOException | JSONException e) {
//                            LogUtil.error(getClassName(), e, e.getMessage());
//                        }
//                    });
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }

        return null;
    }

    private JSONObject buildHttpBody(String to, String topic, String processDefId, String activityDefId, String activityName, String activityId, String processId, String processName, String title, String content, WorkflowAssignment wfAssignment)throws JSONException {
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
        jsonData.put("activityDefId", activityDefId);
        jsonData.put("processId", processId);
        jsonData.put("processName", processName);
        jsonData.put("appId", appDefinition.getAppId());
        jsonData.put("appVersion", appDefinition.getVersion());
        jsonData.put("formId", getFormFromActivity(processDefId, activityDefId));
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
    private String getFormFromActivity(String processDefId, String activityDefId) {
        if(activityDefId == null)
            return null;

        AppDefinition appDefinition = AppUtil.getCurrentAppDefinition();
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        PackageActivityForm packageActivityForm = appService.retrieveMappedForm(appDefinition.getAppId(), String.valueOf(appDefinition.getVersion()), processDefId, activityDefId);
        return packageActivityForm == null ? null : packageActivityForm.getFormId();
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

        HttpPost request = new HttpPost(NOTIFICATION_SERVER);
        request.addHeader("Content-Type", CONTENT_TYPE);
        request.addHeader("Authorization", "key=" + getPropertyString("authorization"));
        request.setEntity(new StringEntity(data.toString()));

        try(CloseableHttpClient client = HttpClientBuilder.create().build();
            CloseableHttpResponse response = client.execute(request)) {

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

    @Override
    public void webService(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        boolean isAdmin = WorkflowUtil.isCurrentUserInRole((String)"ROLE_ADMIN");
        if (!isAdmin) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String action = request.getParameter("action");
        ApplicationContext ac = AppUtil.getApplicationContext();
        AppService appService = (AppService)ac.getBean("appService");
        WorkflowManager workflowManager = (WorkflowManager)ac.getBean("workflowManager");
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        if ("getProcesses".equals(action)) {
            try {
                JSONArray jsonArray = new JSONArray();
                Long packageVersion = Optional.of(appDef)
                        .map(AppDefinition::getPackageDefinition)
                        .map(PackageDefinition::getVersion)
                        .map(Long::new)
                        .orElse(0L);

                Collection<WorkflowProcess> processList = workflowManager.getProcessList(appDef.getAppId(), packageVersion.toString());
                HashMap<String, String> empty = new HashMap<>();
                empty.put("value", "");
                empty.put("label", "");
                jsonArray.put(empty);
                for (WorkflowProcess p : processList) {
                    HashMap<String, String> option = new HashMap<String, String>();
                    option.put("value", p.getIdWithoutVersion());
                    option.put("label", p.getName() + " (" + p.getIdWithoutVersion() + ")");
                    jsonArray.put(option);
                }
                jsonArray.write(response.getWriter());
            }
            catch (Exception ex) {
                LogUtil.error(getClassName(), ex, "Get Process options Error!");
            }
        } else if ("getActivities".equals(action)) {
            try {
                JSONArray jsonArray = new JSONArray();
                HashMap<String, String> empty = new HashMap<>();
                empty.put("value", "");
                empty.put("label", "");
                jsonArray.put(empty);
                String processId = request.getParameter("processId");
                if (!"null".equalsIgnoreCase(processId) && !processId.isEmpty()) {
                    String processDefId = "";
                    if (appDef != null) {
                        WorkflowProcess process = appService.getWorkflowProcessForApp(appDef.getId(), appDef.getVersion().toString(), processId);
                        processDefId = process.getId();
                    }
                    Collection<WorkflowActivity> activityList = workflowManager.getProcessActivityDefinitionList(processDefId);
                    for (WorkflowActivity a : activityList) {
                        if (a.getType().equals("route") || a.getType().equals("tool")) continue;
                        HashMap<String, String> option = new HashMap<String, String>();
                        option.put("value", a.getActivityDefId());
                        option.put("label", a.getName() + " (" + a.getActivityDefId() + ")");
                        jsonArray.put(option);
                    }
                }
                jsonArray.write(response.getWriter());
            } catch (Exception ex) {
                LogUtil.error(getClass().getName(), ex, "Get activity options Error!");
            }
        } else if("getParticipants".equals(action)) {
            try {
                JSONArray jsonArray = new JSONArray();
                HashMap<String, String> empty = new HashMap<String, String>();
                empty.put("value", "");
                empty.put("label", "");
                jsonArray.put(empty);
                String processId = request.getParameter("processId");
                if (!"null".equalsIgnoreCase(processId) && !processId.isEmpty()) {
                    String processDefId = "";
                    if (appDef != null) {
                        WorkflowProcess process = appService.getWorkflowProcessForApp(appDef.getId(), appDef.getVersion().toString(), processId);
                        processDefId = process.getId();
                    }

                    Collection<WorkflowParticipant> participantList = workflowManager.getProcessParticipantDefinitionList(processDefId);
                    for (WorkflowParticipant p : participantList) {
                        HashMap<String, String> option = new HashMap<>();
                        option.put("value", p.getId());
                        option.put("label", p.getName());
                        jsonArray.put(option);
                    }
                }
                jsonArray.write(response.getWriter());
            } catch (Exception ex) {
                LogUtil.error(getClassName(), ex, "Get Process options Error!");
            }
        } else {
            response.setStatus(204);
        }
    }
}
