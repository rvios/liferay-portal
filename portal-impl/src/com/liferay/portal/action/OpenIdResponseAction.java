/**
 * Copyright (c) 2000-2008 Liferay, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.liferay.portal.action;

import com.liferay.portal.DuplicateUserEmailAddressException;
import com.liferay.portal.NoSuchUserException;
import com.liferay.portal.kernel.servlet.SessionErrors;
import com.liferay.portal.kernel.servlet.SessionMessages;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.model.User;
import com.liferay.portal.service.ServiceContext;
import com.liferay.portal.service.UserLocalServiceUtil;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portal.util.OpenIdUtil;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portal.util.WebKeys;
import com.liferay.util.PwdGenerator;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import javax.portlet.PortletURL;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.struts.action.Action;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;

import org.openid4java.association.AssociationException;
import org.openid4java.consumer.ConsumerException;
import org.openid4java.consumer.ConsumerManager;
import org.openid4java.consumer.VerificationResult;
import org.openid4java.discovery.DiscoveryException;
import org.openid4java.discovery.DiscoveryInformation;
import org.openid4java.discovery.Identifier;
import org.openid4java.message.AuthSuccess;
import org.openid4java.message.MessageException;
import org.openid4java.message.MessageExtension;
import org.openid4java.message.ParameterList;
import org.openid4java.message.ax.AxMessage;
import org.openid4java.message.ax.FetchResponse;
import org.openid4java.message.sreg.SRegMessage;
import org.openid4java.message.sreg.SRegResponse;

/**
 * <a href="OpenIdResponseAction.java.html"><b><i>View Source</i></b></a>
 *
 * @author Jorge Ferrer
 *
 */
public class OpenIdResponseAction extends Action {

	public ActionForward execute(
			ActionMapping mapping, ActionForm form, HttpServletRequest request,
			HttpServletResponse response)
		throws Exception {

		ThemeDisplay themeDisplay = (ThemeDisplay)request.getAttribute(
			WebKeys.THEME_DISPLAY);

		if (!OpenIdUtil.isEnabled(themeDisplay.getCompanyId())) {
			return null;
		}

		String redirect = null;

		try {
			redirect = readResponse(themeDisplay, request);
		}
		catch (Exception e) {
			if (e instanceof AssociationException ||
				e instanceof ConsumerException ||
				e instanceof DiscoveryException ||
				e instanceof DuplicateUserEmailAddressException ||
				e instanceof MessageException) {

				SessionErrors.add(request, e.getClass().getName());

				return mapping.findForward("portal.login");
			}
			else {
				_log.error("Error processing OpenID response", e);

				PortalUtil.sendError(e, request, response);

				return null;
			}
		}

		if (Validator.isNull(redirect)) {
			redirect =
				PortalUtil.getPortalURL(request) + themeDisplay.getURLSignIn();
		}

		response.sendRedirect(redirect);

		return null;
	}

	protected User addUser(
			long companyId, String firstName, String lastName,
			String emailAddress, String openId, Locale locale)
		throws Exception {

		long creatorUserId = 0;
		boolean autoPassword = false;
		String password1 = PwdGenerator.getPassword();
		String password2 = password1;
		boolean autoScreenName = true;
		String screenName = StringPool.BLANK;
		String middleName = StringPool.BLANK;
		int prefixId = 0;
		int suffixId = 0;
		boolean male = true;
		int birthdayMonth = Calendar.JANUARY;
		int birthdayDay = 1;
		int birthdayYear = 1970;
		String jobTitle = StringPool.BLANK;
		long[] groupIds = null;
		long[] organizationIds = null;
		long[] roleIds = null;
		long[] userGroupIds = null;
		boolean sendEmail = false;
		ServiceContext serviceContext = null;

		User user = UserLocalServiceUtil.addUser(
			creatorUserId, companyId, autoPassword, password1, password2,
			autoScreenName, screenName, emailAddress, openId, locale, firstName,
			middleName, lastName, prefixId, suffixId, male, birthdayMonth,
			birthdayDay, birthdayYear, jobTitle, groupIds, organizationIds,
			roleIds, userGroupIds, sendEmail, serviceContext);

		return user;
	}

	protected String getFirstValue(List<String> values) {
		if ((values == null) || (values.size() < 1)) {
			return null;
		}

		return values.get(0);
	}

	protected String readResponse(
			ThemeDisplay themeDisplay, HttpServletRequest request)
		throws Exception {

		HttpSession session = request.getSession();

		ConsumerManager manager = OpenIdUtil.getConsumerManager();

		ParameterList params = new ParameterList(request.getParameterMap());

		DiscoveryInformation discovered =
			(DiscoveryInformation)session.getAttribute(WebKeys.OPEN_ID_DISCO);

		if (discovered == null) {
			return null;
		}

		StringBuffer receivingURL = request.getRequestURL();
		String queryString = request.getQueryString();

		if ((queryString != null) && (queryString.length() > 0)) {
			receivingURL.append(StringPool.QUESTION);
			receivingURL.append(request.getQueryString());
		}

		VerificationResult verification = manager.verify(
			receivingURL.toString(), params, discovered);

		Identifier verified = verification.getVerifiedId();

		if (verified == null) {
			return null;
		}

		AuthSuccess authSuccess = (AuthSuccess)verification.getAuthResponse();

		String firstName = null;
		String lastName = null;
		String emailAddress = null;

		if (authSuccess.hasExtension(SRegMessage.OPENID_NS_SREG)) {
			MessageExtension ext = authSuccess.getExtension(
				SRegMessage.OPENID_NS_SREG);

			if (ext instanceof SRegResponse) {
				SRegResponse sregResp = (SRegResponse)ext;

				String fullName = GetterUtil.getString(
					sregResp.getAttributeValue("fullname"));

				int pos = fullName.indexOf(StringPool.SPACE);

				if ((pos != -1) && ((pos + 1) < fullName.length())) {
					firstName = fullName.substring(0, pos);
					lastName = fullName.substring(pos + 1);
				}

				emailAddress = sregResp.getAttributeValue("email");
			}
		}

		if (authSuccess.hasExtension(AxMessage.OPENID_NS_AX)) {
			MessageExtension ext = authSuccess.getExtension(
				AxMessage.OPENID_NS_AX);

			if (ext instanceof FetchResponse) {
				FetchResponse fetchResp = (FetchResponse)ext;

				if (Validator.isNull(firstName)) {
					firstName = getFirstValue(
						fetchResp.getAttributeValues("firstName"));
				}

				if (Validator.isNull(lastName)) {
					lastName = getFirstValue(
						fetchResp.getAttributeValues("lastName"));
				}

				if (Validator.isNull(emailAddress)) {
					emailAddress = getFirstValue(
						fetchResp.getAttributeValues("email"));
				}
			}
		}

		String openId = OpenIdUtil.normalize(authSuccess.getIdentity());

		User user = null;

		try {
			user = UserLocalServiceUtil.getUserByOpenId(openId);
		}
		catch (NoSuchUserException nsue) {
			if (Validator.isNull(firstName) || Validator.isNull(lastName) ||
				Validator.isNull(emailAddress)) {

				SessionMessages.add(request, "missingOpenIdUserInformation");

				if (_log.isInfoEnabled()) {
					_log.info(
						"The OpenID provider did not send the required " +
							"attributes to create an account");
				}

				PortletURL createAccountURL =
					themeDisplay.getURLCreateAccount();

				createAccountURL.setParameter("openId", openId);

				session.setAttribute(
					WebKeys.OPEN_ID_LOGIN_PENDING, Boolean.TRUE);

				return createAccountURL.toString();
			}

			user = addUser(
				themeDisplay.getCompanyId(), firstName, lastName, emailAddress,
				openId, themeDisplay.getLocale());
		}

		session.setAttribute(WebKeys.OPEN_ID_LOGIN, new Long(user.getUserId()));

		return null;
	}

	private static Log _log = LogFactory.getLog(OpenIdResponseAction.class);

}