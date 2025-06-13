package com.kinnarastudio.kecakplugins.pushnotification;

import com.kinnarastudio.kecakplugins.pushnotification.commons.FcmPushNotificationMixin;
import com.kinnarastudio.commons.Try;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.PackageDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.PluginThread;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.plugin.base.PluginManager;
import org.joget.plugin.base.PluginWebSupport;
import org.joget.workflow.model.WorkflowActivity;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.WorkflowParticipant;
import org.joget.workflow.model.WorkflowProcess;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.context.ApplicationContext;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FcmAssignmentPushNotificationTool extends DefaultApplicationPlugin implements PluginWebSupport, FcmPushNotificationMixin {
    private static boolean fcmInitialized = false;

    public final static String LABEL = "FCM Assignment Push Notification Tool";

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
        return AppUtil.readPluginResource(getClassName(), "/properties/FcmInboxPushNotificationTool.json", new String[]{getClassName(), getClassName(), getClassName()}, true, "/messages/InboxNotificationTool");
    }

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

    @Override
    public Object execute(Map props) {
        final PluginManager pluginManager = (PluginManager) props.get("pluginManager");
        final WorkflowManager workflowManager = (WorkflowManager) pluginManager.getBean("workflowManager");
        final WorkflowUserManager workflowUserManager = (WorkflowUserManager) pluginManager.getBean("workflowUserManager");
        final AppDefinition appDefinition = (AppDefinition) props.get("appDef");

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {

            if (!fcmInitialized) {
                String projectId = getProjectId(props);
                JSONObject jsonPrivateKey = new JSONObject(props.get("jsonPrivateKey").toString());
                initializeSdk(projectId, jsonPrivateKey);
                fcmInitialized = true;
            }

            WorkflowAssignment workflowAssignment = (WorkflowAssignment) props.get("workflowAssignment");
            if (workflowAssignment == null) {
                LogUtil.warn(getClassName(), "No assignment found");
                return null;
            }

            String toParticipantIds = getPropertyString("participantId");
            String[] toUserId = getPropertyString("userId").split("[;,]");

            final String title = props.get("notificationTitle").toString();
            final String content = props.get("notificationContent").toString();
            final String processId = workflowAssignment.getProcessId();

            Set<String> assignmentUsers = Arrays.stream(toParticipantIds.split("[,;]"))
                    .map(String::trim)
                    .filter(p -> !p.isEmpty())
                    .map(participantId -> WorkflowUtil.getAssignmentUsers(

                            WorkflowUtil.getProcessDefPackageId(workflowAssignment.getProcessDefId()),
                            workflowAssignment.getProcessDefId(),
                            workflowAssignment.getProcessId(),
                            workflowAssignment.getProcessVersion(),
                            workflowAssignment.getActivityId(),
                            "",
                            participantId
                    ))
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());

            new PluginThread(Try.onRunnable(() -> {
                Thread.sleep(3000);

                final Collection<String> users = Stream.concat(assignmentUsers.stream(), Arrays.stream(toUserId))
                        .collect(Collectors.toSet());

                long notificationCount = sendNotifications(appDefinition, users, processId, title, content);

                if (notificationCount == 0) {
                    LogUtil.warn(getClassName(), "Nobody received the notification");
                }

            })).start();

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
        } catch (JSONException | IOException e) {
            LogUtil.error(getClassName(), e, e.getMessage());
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }

        return null;
    }

    public String getProjectId(Map props) {
        return props.get("fcmDatabaseUrl").toString();
    }

    @Override
    public void webService(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        boolean isAdmin = WorkflowUtil.isCurrentUserInRole("ROLE_ADMIN");
        if (!isAdmin) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String action = request.getParameter("action");
        ApplicationContext ac = AppUtil.getApplicationContext();
        AppService appService = (AppService) ac.getBean("appService");
        WorkflowManager workflowManager = (WorkflowManager) ac.getBean("workflowManager");
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
            } catch (Exception ex) {
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
        } else if ("getParticipants".equals(action)) {
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
