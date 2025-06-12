package com.kinnarastudio.kecakplugins.pushnotification;

import java.util.ArrayList;
import java.util.Collection;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class Activator implements BundleActivator {

    protected Collection<ServiceRegistration> registrationList;

    public void start(BundleContext context) {
        registrationList = new ArrayList<ServiceRegistration>();

        //Register plugin here
        registrationList.add(context.registerService(FcmPushNotificationAuditTrail.class.getName(), new FcmPushNotificationAuditTrail(), null));
        registrationList.add(context.registerService(FcmAssignmentPushNotificationTool.class.getName(), new FcmAssignmentPushNotificationTool(), null));
        registrationList.add(context.registerService(FcmPushNotificationParticipant.class.getName(), new FcmPushNotificationParticipant(), null));
    }

    public void stop(BundleContext context) {
        for (ServiceRegistration registration : registrationList) {
            registration.unregister();
        }
    }
}