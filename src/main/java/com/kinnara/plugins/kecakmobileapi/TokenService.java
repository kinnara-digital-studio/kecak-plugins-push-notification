package com.kinnara.plugins.kecakmobileapi;

import com.kinnara.plugins.kecakmobileapi.exceptions.RestApiException;
import io.jsonwebtoken.*;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.workflow.security.WorkflowUserDetails;
import org.joget.directory.model.User;
import org.joget.directory.model.service.ExtDirectoryManager;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class TokenService {
    protected static final String SECRET = "ThisIsASecretCode";
    protected static final String HEADER_STRING = "Authorization";
    protected static final String ISSUER = "eApproval";
    protected static final String DEVICE_ID = "device_id";
    protected static final String FCM_TOKEN = "fcm_token";

    public static boolean authenticateAndSetThreadUser(@Nonnull HttpServletRequest request) throws RestApiException {

        User user = TokenService.getAuthentication(request);
        if (user == null) {
            throw new RestApiException(HttpServletResponse.SC_UNAUTHORIZED, "user not found");
        }

        WorkflowUserDetails userDetail = new WorkflowUserDetails(user);
        Authentication auth = new UsernamePasswordAuthenticationToken(userDetail, userDetail.getUsername(), userDetail.getAuthorities());

        //Login the user
        SecurityContextHolder.getContext().setAuthentication(auth);
        ApplicationContext appContext = AppUtil.getApplicationContext();
        WorkflowUserManager wfUserManager = (WorkflowUserManager) appContext.getBean("workflowUserManager");
        wfUserManager.setCurrentThreadUser(user.getUsername());
        return true;
    }

    /**
     *
     * @param request HTTP Request
     * @return Logged-in user based on @param request
     * @throws /Exception
     */
    public static User getAuthentication (HttpServletRequest request) throws RestApiException  {
        String session = request.getHeader(HEADER_STRING);
        if (session != null) {
            try {
                String user = Jwts
                        .parser()
                        .setSigningKey(SECRET)
                        .parseClaimsJws(session.replaceAll("^Bearer ", ""))
                        .getBody().getSubject();

                if (user.equals("")) {
                    throw new RestApiException(HttpServletResponse.SC_UNAUTHORIZED, "user is empty");
                } else {
                    ApplicationContext ac = AppUtil.getApplicationContext();
                    ExtDirectoryManager dm = (ExtDirectoryManager) ac.getBean("directoryManager");
                    WorkflowUserManager wfUserManager = (WorkflowUserManager) AppUtil.getApplicationContext().getBean("workflowUserManager");
                    wfUserManager.setCurrentThreadUser(user);
                    return dm.getUserByUsername(user);
                }
            } catch (JwtException e) {
                throw new RestApiException(HttpServletResponse.SC_UNAUTHORIZED,"invalid token");
            }
        } else {
            // get from web session
//            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
//            if (authentication != null) {
//                String username = authentication.getName();
//                if (!"roleAnonymous".equals(username)) {
//                    ApplicationContext ac = AppUtil.getApplicationContext();
//                    ExtDirectoryManager dm = (ExtDirectoryManager) ac.getBean("directoryManager");
//                    return dm.getUserByUsername(username);
//                }
//            }
            throw new RestApiException(HttpServletResponse.SC_UNAUTHORIZED,"token is not found");
        }
    }

    public static Claims getClaims (HttpServletRequest request) {

        String fcmToken = request.getHeader(HEADER_STRING);
        Claims retClaims = null;

        if (fcmToken != null) {
            Jws<Claims> claims = Jwts.parser()
                    .setSigningKey(SECRET)
                    .parseClaimsJws(fcmToken.replaceAll("^Bearer ", ""));
            retClaims = claims.getBody();
        }
        return retClaims;
    }
}
