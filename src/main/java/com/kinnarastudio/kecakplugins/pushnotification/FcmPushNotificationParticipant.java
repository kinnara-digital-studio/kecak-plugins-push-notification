package com.kinnarastudio.kecakplugins.pushnotification;

import com.kinnarastudio.commons.Try;
import com.kinnarastudio.kecakplugins.pushnotification.commons.FcmPushNotificationMixin;
import org.apache.commons.mail.EmailAttachment;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.HtmlEmail;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.service.FileUtil;
import org.joget.commons.util.*;
import org.joget.plugin.base.PluginException;
import org.joget.plugin.base.PluginManager;
import org.joget.workflow.model.DefaultParticipantPlugin;
import org.joget.workflow.model.ParticipantPlugin;
import org.joget.workflow.model.WorkflowActivity;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;

import javax.activation.FileDataSource;
import javax.annotation.Nonnull;
import javax.mail.internet.MimeUtility;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

public class FcmPushNotificationParticipant extends DefaultParticipantPlugin implements FcmPushNotificationMixin {
    public final static String LABEL = "FCM Push Notification Participant";
    private boolean fcmInitialized = false;

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
    public Collection<String> getActivityAssignments(Map properties) {
        ApplicationContext applicationContext = AppUtil.getApplicationContext();
        WorkflowActivity workflowActivity = (WorkflowActivity) properties.get("workflowActivity");
        PluginManager pluginManager = (PluginManager) applicationContext.getBean("pluginManager");
        WorkflowManager workflowManager = (WorkflowManager) applicationContext.getBean("workflowManager");
        ParticipantPlugin participantPlugin = pluginManager.getPlugin((Map<String, Object>) getProperty("participantPlugin"));
        AppDefinition appDefinition = AppUtil.getCurrentAppDefinition();

        String notificationTitle = getNotificationTitle(properties);
        String notificationContent = getNotificationContent(properties);
        String authorization = getAuthorization(properties);

        Collection<String> users = participantPlugin.getActivityAssignments(properties);
        WorkflowActivity activityDefinition = workflowManager.getProcessActivityDefinition(workflowActivity.getProcessDefId(), workflowActivity.getActivityDefId());
        if (WorkflowActivity.TYPE_NORMAL.equals(activityDefinition.getType())) {
            ///////////////////////////////SEND EMAIL
            this.sendEmailNotif(properties);

            ///////////////////////////////END SEND EMAIL
            try {
                if (!fcmInitialized) {
                    String projectId = getProjectId(properties);
                    JSONObject jsonPrivateKey = getJsonPrivateKey(properties);
                    initializeSdk(projectId, jsonPrivateKey);
                    fcmInitialized = true;
                }

                long notificationCount = sendNotifications(appDefinition, users, workflowActivity, authorization, notificationTitle, notificationContent);

                if (notificationCount == 0) {
                    LogUtil.warn(getClassName(), "No notification has been sent");
                }
            } catch (IndexOutOfBoundsException | IOException e) {
                LogUtil.error(getClassName(), e, e.getMessage());
            }
        }

        return users;
    }

    private void sendEmailNotif(Map properties) {
        String formDataTable = (String) properties.get("formDataTable");
        String smtpHost = (String) properties.get("host");
        String smtpPort = (String) properties.get("port");
        String smtpUsername = (String) properties.get("username");
        String smtpPassword = (String) properties.get("password");
        String security = (String) properties.get("security");

        final String from = (String) properties.get("from");
        final String cc = (String) properties.get("cc");
        final String bcc = (String) properties.get("bcc");
        String toParticipantId = (String) properties.get("toParticipantId");
        String toSpecific = (String) properties.get("toSpecific");

        String emailSubject = (String) properties.get("subject");
        String emailMessage = (String) properties.get("messages");

        String isHtml = (String) properties.get("isHtml");

        WorkflowAssignment wfAssignment = (WorkflowAssignment) properties.get("workflowAssignment");
        AppDefinition appDef = (AppDefinition) properties.get("appDef");

        DataSource ds = (DataSource) AppUtil.getApplicationContext().getBean("setupDataSource");

        try {
            Map<String, String> replaceMap = null;
            if ("true".equalsIgnoreCase(isHtml)) {
                replaceMap = new HashMap<String, String>();
                replaceMap.put("\\n", "<br/>");
            }

            toSpecific = AppUtil.processHashVariable(toSpecific, wfAssignment, null, replaceMap);

            emailSubject = WorkflowUtil.processVariable(emailSubject, formDataTable, wfAssignment);
            emailMessage = AppUtil.processHashVariable(emailMessage, wfAssignment, null, replaceMap);

            smtpHost = AppUtil.processHashVariable(smtpHost, wfAssignment, null, null);
            smtpPort = AppUtil.processHashVariable(smtpPort, wfAssignment, null, null);
            smtpUsername = AppUtil.processHashVariable(smtpUsername, wfAssignment, null, null);
            smtpPassword = AppUtil.processHashVariable(smtpPassword, wfAssignment, null, null);
            security = AppUtil.processHashVariable(security, wfAssignment, null, null);

            // create the email message
            final HtmlEmail email = new HtmlEmail();
            email.setHostName(smtpHost);
            if (smtpPort != null && smtpPort.length() != 0) {
                email.setSmtpPort(Integer.parseInt(smtpPort));
            }
            if (smtpUsername != null && !smtpUsername.isEmpty()) {
                if (smtpPassword != null) {
                    smtpPassword = SecurityUtil.decrypt(smtpPassword);
                }
                email.setAuthentication(smtpUsername, smtpPassword);
            }
            if (security != null) {
                if (security.equalsIgnoreCase("SSL")) {
                    email.setSSLOnConnect(true);
                    email.setSSLCheckServerIdentity(true);
                    if (smtpPort != null && smtpPort.length() != 0) {
                        email.setSslSmtpPort(smtpPort);
                    }
                } else if (security.equalsIgnoreCase("TLS")) {
                    email.setStartTLSEnabled(true);
                    email.setSSLCheckServerIdentity(true);
                }
            }
            if (cc != null && cc.length() != 0) {
                Collection<String> ccs = AppUtil.getEmailList(null, cc, wfAssignment, appDef);
                for (String address : ccs) {
                    email.addCc(StringUtil.encodeEmail(address));
                }
            }
            if (bcc != null && bcc.length() != 0) {
                Collection<String> ccs = AppUtil.getEmailList(null, bcc, wfAssignment, appDef);
                for (String address : ccs) {
                    email.addBcc(StringUtil.encodeEmail(address));
                }
            }

            final String fromStr = WorkflowUtil.processVariable(from, formDataTable, wfAssignment);
            email.setFrom(StringUtil.encodeEmail(fromStr));
            email.setSubject(emailSubject);
            email.setCharset("UTF-8");

            if ("true".equalsIgnoreCase(isHtml)) {
                email.setHtmlMsg(emailMessage);
            } else {
                email.setMsg(emailMessage);
            }
            String emailToOutput = "";

            if ((toParticipantId != null && toParticipantId.trim().length() != 0) || (toSpecific != null && toSpecific.trim().length() != 0)) {
                Collection<String> tss = AppUtil.getEmailList(toParticipantId, toSpecific, wfAssignment, appDef);
                for (String address : tss) {
                    email.addTo(StringUtil.encodeEmail(address));
                    emailToOutput += address + ", ";
                }
            } else {
                throw new PluginException("no email specified");
            }

            final String to = emailToOutput;
            @SuppressWarnings("unused") final String profile = DynamicDataSourceManager.getCurrentProfile();

            //handle file attachment
            String formDefId = (String) properties.get("formDefId");

            if (formDefId != null && !formDefId.isEmpty()) {
                AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");

                FormData formData = new FormData();
                String primaryKey = appService.getOriginProcessId(wfAssignment.getProcessId());

                String tableName = appService.getFormTableName(appDef, formDefId);
                String foreignKeyId = (String) properties.get("foreignKeyId");
                String foreignKeyValue = (String) properties.get("foreignKeyValue");
                boolean loadByForeignKey = false;
                if ((foreignKeyId != null && !foreignKeyId.equals("")) &&
                        (foreignKeyValue != null && !foreignKeyValue.equals(""))) {
                    loadByForeignKey = true;
                    primaryKey = foreignKeyValue;
                }

                // Load file name in DB
                // Build Query First
                StringBuilder sql = new StringBuilder("SELECT id ");
                sql.append(" FROM app_fd_").append(tableName).append(" ");
                sql.append(" WHERE ");
                if (loadByForeignKey) {
                    sql.append("c_").append(foreignKeyId).append(" ");
                } else {
                    sql.append("id").append(" ");
                }
                sql.append("= ").append("?");

                // Create DB Connection
                try (Connection con = ds.getConnection();
                     PreparedStatement ps = con.prepareStatement(sql.toString());) {
                    //                    LogUtil.info(this.getClass().getName(), sql.toString());
                    ps.setString(1, primaryKey);
                    try (ResultSet rs = ps.executeQuery();) {
                        while (rs.next()) {
                            String uploadPath = FileUtil.getUploadPath(tableName, rs.getString("id"));
                            File dir = new File(uploadPath);
                            for (File file : dir.listFiles()) {
                                if (file != null) {
                                    FileDataSource fds = new FileDataSource(file);
                                    email.attach(fds, MimeUtility.encodeText(file.getName()), "");
                                }
                            }
                            //                        LogUtil.info(this.getClass().getName(), "UPLOAD PATH: "+uploadPath);
                        }
                    } catch (EmailException e) {
                        LogUtil.error(this.getClassName(), e, "");
                    }
                }
            }

            Object[] files = null;
            if (properties.get("files") instanceof Object[]) {
                files = (Object[]) properties.get("files");
            }
            if (files != null && files.length > 0) {
                for (Object o : files) {
                    @SuppressWarnings("rawtypes")
                    Map mapping = (HashMap) o;
                    String path = mapping.get("path").toString();
                    String fileName = mapping.get("fileName").toString();
                    String type = mapping.get("type").toString();

                    try {

                        if ("system".equals(type)) {
                            EmailAttachment attachment = new EmailAttachment();
                            attachment.setPath(path);
                            attachment.setName(MimeUtility.encodeText(fileName));
                            email.attach(attachment);
                        } else {
                            URL u = new URL(path);
                            email.attach(u, MimeUtility.encodeText(fileName), "");
                        }

                    } catch (UnsupportedEncodingException e) {
                        LogUtil.info(this.getClassName(), "Attached file fail from path \"" + path + "\"");
                    } catch (EmailException e) {
                        LogUtil.info(this.getClassName(), "Attached file fail from path \"" + path + "\"");
                    } catch (MalformedURLException e) {
                        LogUtil.info(this.getClassName(), "Attached file fail from path \"" + path + "\"");
                    }
                }
            }

            Thread emailThread = new PluginThread(Try.onRunnable(() -> {
                LogUtil.info(this.getClass().getName(), "EmailTool: Sending email from=" + fromStr + ", to=" + to + "cc=" + cc + ", bcc=" + bcc + ", subject=" + email.getSubject());
                email.send();
                LogUtil.info(this.getClass().getName(), "EmailTool: Sending email completed for subject=" + email.getSubject());
            }));

            emailThread.setDaemon(true);
            emailThread.start();

        } catch (NumberFormatException | EmailException | UnsupportedEncodingException | SQLException | BeansException |
                 PluginException e) {
            LogUtil.error(this.getClass().getName(), e, "");
        }
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
        return AppUtil.readPluginResource(getClassName(), "/properties/FcmPushNotificationParticipant.json", null, false, "/messages/inboxNotificationTool");
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

    @Nonnull
    protected JSONObject getJsonPrivateKey(Map properties) {
        try {
            return new JSONObject(properties.getOrDefault("jsonPrivateKey", "{}").toString());
        } catch (JSONException e) {
            LogUtil.error(getClassName(), e, e.getMessage());
            return new JSONObject();
        }
    }

    @Nonnull
    public String getProjectId(Map properties) {
        return properties.getOrDefault("fcmDatabaseUrl", "").toString();
    }
}
