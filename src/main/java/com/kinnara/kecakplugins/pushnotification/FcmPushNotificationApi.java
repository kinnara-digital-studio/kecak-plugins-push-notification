package com.kinnara.kecakplugins.pushnotification;

import com.kinnara.kecakplugins.pushnotification.commons.ApiException;
import com.kinnara.kecakplugins.pushnotification.commons.FcmPushNotificationMixin;
import com.kinnarastudio.commons.Try;
import com.kinnarastudio.commons.jsonstream.JSONStream;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.plugin.base.PluginWebSupport;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.WorkflowProcessLink;
import org.joget.workflow.model.dao.WorkflowProcessLinkDao;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.context.ApplicationContext;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class FcmPushNotificationApi extends DefaultApplicationPlugin implements PluginWebSupport, FcmPushNotificationMixin {
    private final static String NOTIFICATION_SERVER = "https://fcm.googleapis.com/fcm/send";
    private final static String CONTENT_TYPE = "application/json";

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
    public Object execute(Map map) {
        return null;
    }

    @Override
    public String getLabel() {
        return "FCM Push Notification API";
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return "";
    }

    @Override
    public void webService(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        LogUtil.info(getClass().getName(), "Executing Rest API [" + request.getRequestURI() + "] in method [" + request.getMethod() + "] contentType ["+ request.getContentType() + "] as [" + WorkflowUtil.getCurrentUsername() + "]");

        try {
            JSONObject jsonBody = getRequestBody(request);
            if(!fcmInitialized) {
                JSONObject privateKey = getRequiredJsonBodyContent(jsonBody, "jsonPrivateKey");
                initializeSdk(privateKey);
                fcmInitialized = true;
            }

            List<String> assignmentUsers = JSONStream.of(getRequiredArrayBodyContent(jsonBody, "users"), JSONArray::optString)
                    .collect(Collectors.toList());

            String processId = getRequiredBodyContent(jsonBody, "processId");
            WorkflowAssignment assignment = getAssignmentByProcess(processId);

            String notificationTitle = getRequiredBodyContent(jsonBody, "title");

            String notificationContent = getRequiredBodyContent(jsonBody, "content");

            long notificationCount = assignmentUsers.stream()
                    .filter(Objects::nonNull)
                    .filter(u -> !u.isEmpty())
                    .distinct()
                    .peek(u -> LogUtil.info(getClassName(), "Sending notification for process [" + assignment.getProcessId() + "] to user [" + u + "]"))
                    .filter(username -> {
                        try {
                            JSONObject jsonHttpPayload = getNotificationPayload(
                                    null,
                                    username,
                                    assignment,
                                    AppUtil.processHashVariable(notificationTitle, assignment, null, null),
                                    AppUtil.processHashVariable(notificationContent, assignment, null, null));

                            HttpResponse pushNotificationResponse = pushNotification(getPropertyString("authorization"), jsonHttpPayload, true);

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
                    .peek(u -> LogUtil.info(getClassName(), "Notification for process [" + assignment.getProcessId() + "] to user [" + u + "] has been sent"))
                    .count();

            if(notificationCount == 0) {
                throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "No notification has been sent");
            }

        } catch (ApiException e) {
            response.sendError(e.getErrorCode(), e.getMessage());
        }
    }

    protected InputStream getServiceAccount(JSONObject body) throws ApiException {
        try {
            return new ByteArrayInputStream(body.getString("privateKey").getBytes());
        } catch (JSONException e) {
            throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, e);
        }
    }

    protected JSONObject getRequestBody(HttpServletRequest request) throws ApiException {
        try(BufferedReader br = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
            return new JSONObject(br.lines().collect(Collectors.joining()));
        } catch (IOException | JSONException e) {
            throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, e);
        }
    }

    protected String getRequiredParameter(HttpServletRequest request, String parameterName) throws ApiException {
        return Optional.of(parameterName)
                .map(request::getParameter)
                .orElseThrow(() -> new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Parameter ["+parameterName+"] is not supplied"));
    }

    protected String getRequiredBodyContent(JSONObject jsonBody, String parameterName) throws ApiException {
        return Optional.of(parameterName)
                .map(Try.onFunction(jsonBody::getString))
                .orElseThrow(() -> new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Parameter ["+parameterName+"] is not supplied"));
    }

    protected JSONObject getRequiredJsonBodyContent(JSONObject jsonBody, String parameterName) throws ApiException {
        return Optional.of(parameterName)
                .map(Try.onFunction(jsonBody::getJSONObject))
                .orElseThrow(() -> new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Parameter [" + parameterName +"] is not supplied or not array"));
    }

    protected JSONArray getRequiredArrayBodyContent(JSONObject jsonBody, String parameterName) throws ApiException {
        return Optional.of(parameterName)
                .map(Try.onFunction(jsonBody::getJSONArray))
                .orElseThrow(() -> new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Parameter [" + parameterName +"] is not supplied or not array"));
    }

    @Nonnull
    protected WorkflowAssignment getAssignmentByProcess(@Nonnull String processId) throws ApiException {
        ApplicationContext applicationContext = AppUtil.getApplicationContext();
        WorkflowProcessLinkDao workflowProcessLinkDao = (WorkflowProcessLinkDao) applicationContext.getBean("workflowProcessLinkDao");
        WorkflowManager workflowManager = (WorkflowManager) applicationContext.getBean("workflowManager");

        return Optional.of(processId)
                .map(workflowProcessLinkDao::getLinks)
                .map(Collection::stream)
                .orElseThrow(() -> new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Process [" + processId + "] is not defined"))
                .findFirst()
                .map(WorkflowProcessLink::getProcessId)
                .map(Try.onFunction(workflowManager::getAssignmentByProcess))
                .orElseThrow(() -> new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Assignment for process [" + processId + "] not available"));
    }
}
