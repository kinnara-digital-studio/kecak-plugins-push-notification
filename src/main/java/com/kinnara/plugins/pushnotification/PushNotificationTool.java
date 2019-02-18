package com.kinnara.plugins.pushnotification;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.joget.apps.app.dao.FormDefinitionDao;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.FormDefinition;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.Form;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.service.FormService;
import org.joget.apps.form.service.FormUtil;
import org.joget.commons.util.LogUtil;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

public class PushNotificationTool extends DefaultApplicationPlugin {
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
        return "Kecak Mobile API - Push Notification Tool";
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
                    .peek(p -> LogUtil.info(getClassName(), "participantId ["+p+"]"))
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

            if (assignmentUsers == null || assignmentUsers.isEmpty()) {
                LogUtil.warn(getClassName(), "No user for assignment [" + activityAssignment.getActivityId() + "] participant [" + toParticipantId + "]");
                return null;
            }

            Form form = generateForm(getPropertyString("formId"), formCache);

            assignmentUsers
                    .stream()
                    .filter(Objects::nonNull)
                    .filter(u -> !u.isEmpty())
                    .peek(u -> LogUtil.info(getClassName(), "Sending notification for process [" + activityAssignment.getProcessId() + "] user [" + u + "]"))
                    .forEach(u -> {
                        try {
                            JSONObject jsonHttpPayload = buildHttpBody(

//                                    "/topics/" + u, //token,
                                    getPropertyString("to"),
                                    null,
                                    form.getPropertyString(FormUtil.PROPERTY_ID),
                                    form.getPropertyString(FormUtil.PROPERTY_LABEL),
                                    activityAssignment.getActivityName(),
                                    activityAssignment.getActivityId(),
                                    activityAssignment.getProcessId(),
                                    activityAssignment.getProcessName(),
                                    AppUtil.processHashVariable(getPropertyString("notificationTitle"), activityAssignment, null, null),
                                    AppUtil.processHashVariable(getPropertyString("notificationContent"), activityAssignment, null, null),
                                    activityAssignment);

                            pushNotification(jsonHttpPayload);
                        } catch (IOException | JSONException e) {
                            e.printStackTrace();
                        }
                    });
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }

        return null;
    }

    private JSONObject buildHttpBody(String to, String topic, String formId, String formLabel, String activityName, String activityId, String processId, String processName, String title, String content, WorkflowAssignment wfAssignment)throws JSONException {
        JSONObject jsonHtmlPayload = new JSONObject();
        if(to != null && !to.equals(""))
        jsonHtmlPayload.put("to", to);
        else jsonHtmlPayload.put("to", "APA91bEd4Rsn61iHBmKvmeElicdxpFeiVKwWSpNDDagsuqsHfOAGYD83WNNteZZNo7_KpZ-Gko3_zH7LEaWQh0_7cNvfVeSIjhmWuchb-2eaLZxyYn7nKoq5rmFyS_LiB_fH2k9QdWivMoFm6_KgC3e47JLe0yVsBA");

        if(topic != null && !topic.isEmpty())
            jsonHtmlPayload.put("topic", topic);

        jsonHtmlPayload.put("content_available", true);

        JSONObject jsonData = new JSONObject();
        jsonData.put("title", title);
        jsonData.put("message", content);
        jsonData.put("formId", formId);
        jsonData.put("formLabel", formLabel);
        jsonData.put("activityName", activityName);
        jsonData.put("activityId", activityId);
        jsonData.put("processId", processId);
        jsonData.put("processName", processName);

        jsonHtmlPayload.put("data", jsonData);


//		{
//			JSONObject jsonNotification = new JSONObject();
//			jsonNotification.put("title", title);
//			jsonNotification.put("body", processName + " : " + activityName);
//
//			jsonHtmlPayload.put("notification", jsonNotification);
//		}
        return jsonHtmlPayload;
    }

    /**
     * Construct form from formId
     /* @param formDefId
     /* @param formCache
     /* @return
     */
    private Form generateForm(String formDefId, Map<String, Form> formCache) {
        return generateForm(formDefId, null, formCache);
    }

    /**
     * Construct form from formId
     /* @param formDefId
     /* @param processId
     /* @param formCache
     /* @return
     */
    private Form generateForm(String formDefId, String processId, Map<String, Form> formCache) {
        // check in cache
        if(formCache != null && formCache.containsKey(formDefId))
            return formCache.get(formDefId);

        // proceed without cache
        FormService formService = (FormService)AppUtil.getApplicationContext().getBean("formService");
        Form form;
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        if (appDef != null && formDefId != null && !formDefId.isEmpty()) {
            FormDefinitionDao formDefinitionDao = (FormDefinitionDao)AppUtil.getApplicationContext().getBean("formDefinitionDao");
            FormDefinition formDef = formDefinitionDao.loadById(formDefId, appDef);
            if (formDef != null) {
                FormData formData = new FormData();
                String json = formDef.getJson();
                if (processId != null && !processId.isEmpty()) {
                    formData.setProcessId(processId);
                    WorkflowManager wm = (WorkflowManager)AppUtil.getApplicationContext().getBean("workflowManager");
                    WorkflowAssignment wfAssignment = wm.getAssignmentByProcess(processId);
                    json = AppUtil.processHashVariable(json, wfAssignment, "json", null);
                }
                form = (Form)formService.createElementFromJson(json);

                // put in cache if possible
                if(formCache != null)
                    formCache.put(formDefId, form);

                return form;
            }
        }
        return null;
    }

    private HttpResponse pushNotification(JSONObject data) throws IOException {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
        try {
            HttpClient client = HttpClientBuilder.create().build();
            HttpPost request = new HttpPost(NOTIFICATION_SERVER);
            request.addHeader("Content-Type", CONTENT_TYPE);
            request.addHeader("Authorization", "key="+getPropertyString("authorization"));
            request.setEntity(new StringEntity(data.toString()));
            return client.execute(request);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
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
