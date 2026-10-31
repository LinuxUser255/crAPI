/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React, { useEffect, useRef, useState } from "react";
import PropTypes from "prop-types";
import { connect } from "react-redux";
import { Modal } from "antd";
import { useLocation, useNavigate } from "react-router-dom";
import OAuthAuthorize from "../../components/oauthAuthorize/oauthAuthorize";
import {
  getOAuthRequestAction,
  decideOAuthRequestAction,
} from "../../actions/oauthActions";
import responseTypes from "../../constants/responseTypes";
import {
  FAILURE_MESSAGE,
  OAUTH_REQUEST_FAILED,
} from "../../constants/messages";

const OAUTH_PARAMS = [
  "response_type",
  "client_id",
  "redirect_uri",
  "scope",
  "state",
];

/** The OAuth parameters present in the query string; absent ones are left out. */
const readParams = (search) => {
  const query = new URLSearchParams(search);
  const params = {};
  OAUTH_PARAMS.forEach((name) => {
    const value = query.get(name);
    if (value !== null) {
      params[name] = value;
    }
  });
  return params;
};

/**
 * Consent page for a third-party app's authorization request (route /oauth-authorize). The app
 * sends the user's browser here; this page validates the request with the user's crAPI token,
 * asks for consent unless it was already given, and sends the browser back to the app.
 */
const OAuthAuthorizeContainer = (props) => {
  const { accessToken, name, email, getOAuthRequest, decideOAuthRequest } =
    props;
  const location = useLocation();
  const navigate = useNavigate();
  const [phase, setPhase] = useState("loading");
  const [info, setInfo] = useState(null);
  const [errorMessage, setErrorMessage] = useState("");
  const [errorRedirectUrl, setErrorRedirectUrl] = useState(null);
  const started = useRef(false);
  const params = readParams(location.search);

  const decide = (approved) => {
    decideOAuthRequest({
      accessToken,
      params,
      approved,
      callback: (res, data) => {
        if (res === responseTypes.REDIRECT) {
          setPhase("redirecting");
          // Leaving the SPA for the client application.
          window.location.assign(data);
        } else {
          Modal.error({ title: FAILURE_MESSAGE, content: data });
        }
      },
    });
  };

  useEffect(() => {
    // One request per page load (React StrictMode runs effects twice in development).
    if (started.current) {
      return;
    }
    started.current = true;

    if (!params.client_id) {
      setErrorMessage("The link you followed is missing the app's client_id.");
      setPhase("error");
      return;
    }

    getOAuthRequest({
      accessToken,
      params,
      callback: (res, data) => {
        if (res === responseTypes.SUCCESS) {
          setInfo(data);
          if (data.consent_required) {
            setPhase("consent");
          } else {
            // Already approved for these scopes: no need to ask again.
            setPhase("redirecting");
            decide(true);
          }
          return;
        }
        setErrorMessage(
          (data && data.error_description) || OAUTH_REQUEST_FAILED,
        );
        // Present only for errors that may be reported to the (validated) redirect URI.
        setErrorRedirectUrl((data && data.redirect_url) || null);
        setPhase("error");
      },
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <OAuthAuthorize
      phase={phase}
      info={info}
      errorMessage={errorMessage}
      errorRedirectUrl={errorRedirectUrl}
      userName={name}
      userEmail={email}
      onApprove={() => decide(true)}
      onDeny={() => decide(false)}
      onReturn={(url) => window.location.assign(url)}
      onLeave={() => navigate("/dashboard")}
    />
  );
};

const mapStateToProps = ({ userReducer: { accessToken, name, email } }) => {
  return { accessToken, name, email };
};

const mapDispatchToProps = {
  getOAuthRequest: getOAuthRequestAction,
  decideOAuthRequest: decideOAuthRequestAction,
};

OAuthAuthorizeContainer.propTypes = {
  accessToken: PropTypes.string,
  name: PropTypes.string,
  email: PropTypes.string,
  getOAuthRequest: PropTypes.func,
  decideOAuthRequest: PropTypes.func,
};

export default connect(
  mapStateToProps,
  mapDispatchToProps,
)(OAuthAuthorizeContainer);
