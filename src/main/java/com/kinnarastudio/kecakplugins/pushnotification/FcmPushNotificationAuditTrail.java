package com.kinnarastudio.kecakplugins.pushnotification;

import com.kinnarastudio.kecakplugins.pushnotification.commons.FcmPushNotificationMixin;
import com.kinnarastudio.commons.Try;
import com.kinnarastudio.commons.jsonstream.JSONCollectors;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.AuditTrail;
import org.joget.apps.app.model.PackageDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.PluginThread;
import org.joget.plugin.base.DefaultAuditTrailPlugin;
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

public class FcmPushNotificationAuditTrail extends DefaultAuditTrailPlugin implements FcmPushNotificationMixin, PluginWebSupport {
    private boolean fcmInitialized = false;

    public final static String LABEL = "FCM Push Notification";

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
    public Object execute(Map properties) {
        final PluginManager pluginManager = (PluginManager) properties.get("pluginManager");
        final WorkflowManager workflowManager = (WorkflowManager) pluginManager.getBean("workflowManager");
        final WorkflowUserManager workflowUserManager = (WorkflowUserManager) pluginManager.getBean("workflowUserManager");
        final AuditTrail auditTrail = (AuditTrail) properties.get("auditTrail");
        final AppDefinition appDefinition = AppUtil.getCurrentAppDefinition();

        final String method = auditTrail.getMethod();
        final Object[] args = auditTrail.getArgs();

        final String activityId;
        final Collection<String> assignmentUsers;

        if ("getDefaultAssignments".equals(method) && args.length == 3) {

            activityId = (String) auditTrail.getArgs()[1];
            assignmentUsers = Collections.unmodifiableCollection((List<String>) auditTrail.getReturnObject());

        } else if ("assignmentReassign".equals(method) && args.length == 5) {

            activityId = (String) auditTrail.getArgs()[0];
            assignmentUsers = Collections.singleton((String) auditTrail.getArgs()[3]);

        } else {
            return null;
        }

        if (!fcmInitialized) {
            try {
                final String databaseUrl = getPropertyDatabaseUrl(null);
                final JSONObject jsonPrivateKey = new JSONObject(getPropertyJsonPrivateKey(null));
                initializeSdk(databaseUrl, jsonPrivateKey);
                fcmInitialized = true;
            } catch (JSONException | IOException e) {
                LogUtil.error(getClassName(), e, e.getMessage());
            }
        }

        new PluginThread(Try.onRunnable(() -> {
            Thread.sleep(5000);

            final long notificationCount = assignmentUsers.stream()
                    .filter(Try.onPredicate(username -> {
                        // filter by activity definition
                        final WorkflowActivity activity = workflowManager.getActivityById(activityId);
                        if(!filter(activity)) {
                            LogUtil.warn(getClassName(), "Activity id [" + activityId + "] is not allowed to send notification");
                            return false;
                        }

                        workflowUserManager.setCurrentThreadUser(username);

                        final WorkflowAssignment assignment = workflowManager.getAssignment(activity.getId());

                        if(assignment == null) {
                            LogUtil.warn(getClassName(), "Assignment id [" + activity.getId() + "] not found for user [" + username + "] ");
                            return false;
                        }

                        final String authorization = getPropertyAuthorization(assignment);
                        final String notificationTitle = getPropertyNotificationTitle(assignment);
                        final String notificationContent = getPropertyNotificationContent(assignment);

                        return sendNotification(appDefinition, username, assignment, notificationTitle, notificationContent);
                    })).count();

            if (notificationCount == 0) {
                LogUtil.warn(getClassName(), "No notification has been sent");
            }
        })).start();


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
        return AppUtil.readPluginResource(getClassName(), "/properties/FcmPushNotificationAuditTrail.json", new Object[] {getClassName(), getClassName()}, false, "/messages/inboxNotificationTool");
    }

    protected String getPropertyDatabaseUrl(WorkflowAssignment assignment) {
        return AppUtil.processHashVariable(getPropertyString("databaseUrl"), assignment, null, null);
    }

    protected String getPropertyJsonPrivateKey(WorkflowAssignment assignment) {
        return AppUtil.processHashVariable(getPropertyString("jsonPrivateKey"), assignment, null, null);
    }

    protected String getPropertyAuthorization(WorkflowAssignment assignment) {
        return AppUtil.processHashVariable(getPropertyString("authorization"), assignment, null, null);
    }

    protected String getPropertyNotificationTitle(WorkflowAssignment assignment) {
        return AppUtil.processHashVariable(getPropertyString("notificationTitle"), assignment, null, null);
    }

    protected String getPropertyNotificationContent(WorkflowAssignment assignment) {
        return AppUtil.processHashVariable(getPropertyString("notificationContent"), assignment, null, null);
    }

    protected Collection<String> getPropertyUserId() {
        return Optional.of("userId")
                .map(this::getPropertyString)
                .map(s -> s.split(";"))
                .map(Arrays::stream)
                .orElseGet(Stream::empty)
                .collect(Collectors.toSet());
    }

    protected String getPropertyProcessId() {
        return getPropertyString("processId");
    }

    protected String getPropertyActivityId() {
        return getPropertyString("activityDefId");
    }

    protected boolean filter(WorkflowActivity activity) {
        return (getPropertyProcessId().isEmpty() || activity.getProcessDefId().equals(getPropertyProcessId()))
                && (getPropertyActivityId().isEmpty() || activity.getActivityDefId().equals(getPropertyActivityId()));
    }

    @Override
    public void webService(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        boolean isAdmin = WorkflowUtil.isCurrentUserInRole((String) "ROLE_ADMIN");
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
                Set<String> processIds = getParameters(request, "processId");

                final JSONArray jsonArray = processIds.stream()
                        .peek(s -> LogUtil.info(getClassName(), "processId ["+s+"]"))
                        .map(s -> appService.getWorkflowProcessForApp(appDef.getId(), appDef.getVersion().toString(), s))
                        .filter(Objects::nonNull)
                        .map(WorkflowProcess::getId)
                        .filter(Objects::nonNull)
                        .peek(s -> LogUtil.info(getClassName(), "processDefId ["+s+"]"))
                        .map(workflowManager::getProcessActivityDefinitionList)
                        .filter(Objects::nonNull)
                        .flatMap(Collection::stream)
                        .filter(a -> !a.getType().equals("route") && !a.getType().equals("tool"))
                        .map(Try.onFunction(a -> {
                            JSONObject jsonOption = new JSONObject();
                            jsonOption.put("value", a.getActivityDefId());
                            jsonOption.put("label", a.getName() + " (" + a.getActivityDefId() + ")");
                            return jsonOption;
                        }))
                        .collect(JSONCollectors.toJSONArray(Try.onSupplier(() -> {
                            final JSONObject empty = new JSONObject();
                            empty.put("value", "");
                            empty.put("label", "");

                            final JSONArray initialArray = new JSONArray();
                            initialArray.put(empty);
                            return initialArray;
                        })));

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

    protected Set<String> getParameters(HttpServletRequest request, String parameterName) {
        return Optional.of(parameterName)
                .map(request::getParameterValues)
                .map(Arrays::stream)
                .orElseGet(Stream::empty)
                .map(s -> s.split("[,;]"))
                .flatMap(Arrays::stream)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
