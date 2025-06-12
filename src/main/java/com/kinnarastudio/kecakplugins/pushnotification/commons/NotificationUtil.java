package com.kinnarastudio.kecakplugins.pushnotification.commons;

import org.joget.apps.app.dao.UserReplacementDao;
import org.joget.apps.app.model.UserReplacement;
import org.joget.apps.app.service.AppUtil;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.WorkflowProcess;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.util.WorkflowUtil;

import java.util.Collection;
import java.util.HashSet;

public final class NotificationUtil {
    public static Collection<String> getUsers(String participantId, WorkflowAssignment assignmentTool) {
        Collection<String> addresses = new HashSet<String>();
        Collection<String> users = new HashSet<>();

        if (participantId != null && !participantId.isEmpty() && assignmentTool != null) {
            WorkflowManager workflowManager = (WorkflowManager) AppUtil.getApplicationContext().getBean("workflowManager");
            WorkflowProcess process = workflowManager.getProcess(assignmentTool.getProcessDefId());
            participantId = participantId.replace(";", ",");
            String pIds[] = participantId.split(",");
            for (String pId : pIds) {
                pId = pId.trim();
                if (pId.isEmpty()) {
                    continue;
                }

                Collection<String> userList = null;
                userList = WorkflowUtil.getAssignmentUsers(process.getPackageId(), assignmentTool.getProcessDefId(), assignmentTool.getProcessId(), assignmentTool.getProcessVersion(), assignmentTool.getActivityId(), "", pId.trim());

                if (userList != null && !userList.isEmpty()) {
                    users.addAll(userList);
                }
            }

            //send to replacement user
            if (!users.isEmpty()) {
                Collection<String> userList = new HashSet<String>();
                String args[] = assignmentTool.getProcessDefId().split("#");

                for (String u : users) {
                    UserReplacementDao urDao = (UserReplacementDao) AppUtil.getApplicationContext().getBean("userReplacementDao");
                    Collection<UserReplacement> replaces = urDao.getUserTodayReplacedBy(u, args[0], args[2]);
                    if (replaces != null && !replaces.isEmpty()) {
                        for (UserReplacement ur : replaces) {
                            userList.add(ur.getReplacementUser());
                        }
                    }
                }

                if (!userList.isEmpty()) {
                    users.addAll(userList);
                }
            }
        }

        return users;
    }
}
