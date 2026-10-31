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

import "./oauthAuthorize.css";
import React from "react";
import { Avatar, Button, Card, Result, Spin } from "antd";
import {
  AppstoreOutlined,
  CheckCircleFilled,
  SwapOutlined,
  UserOutlined,
} from "@ant-design/icons";

/** What each scope lets an app do, in the words the user sees. */
export const SCOPE_DESCRIPTIONS: Record<string, string> = {
  profile: "See your name, email address, phone number and role",
  "read:vehicles": "See your vehicles and where they are",
  "write:orders": "Place and manage shop orders for you",
  mechanic: "Act on your behalf as a mechanic",
  admin: "Full administrative access to your account",
};

export const describeScope = (scope: string): string =>
  SCOPE_DESCRIPTIONS[scope] || scope;

export interface OAuthRequestInfo {
  client_id: string;
  client_name: string;
  logo_url?: string;
  redirect_uri: string;
  scopes: string[];
  state?: string;
  consent_required: boolean;
}

export type OAuthPhase = "loading" | "consent" | "redirecting" | "error";

interface OAuthAuthorizeProps {
  phase: OAuthPhase;
  info: OAuthRequestInfo | null;
  errorMessage: string;
  /** Where the error can be reported back to the app; absent when the app itself is untrusted. */
  errorRedirectUrl: string | null;
  userName: string;
  userEmail: string;
  onApprove: () => void;
  onDeny: () => void;
  onReturn: (url: string) => void;
  onLeave: () => void;
}

const hostOf = (url: string): string => {
  try {
    return new URL(url).host;
  } catch (e) {
    return url;
  }
};

const AppAvatar: React.FC<{ logoUrl?: string }> = ({ logoUrl }) => (
  // Falls back to the icon when the client's logo can't be loaded.
  <Avatar
    size={64}
    src={logoUrl}
    icon={<AppstoreOutlined />}
    className="oauth-app-avatar"
  />
);

/**
 * The consent screen a third-party app sends the user to: who is asking, for what, and where the
 * user will be sent afterwards, with Allow and Deny.
 */
const OAuthAuthorize: React.FC<OAuthAuthorizeProps> = ({
  phase,
  info,
  errorMessage,
  errorRedirectUrl,
  userName,
  userEmail,
  onApprove,
  onDeny,
  onReturn,
  onLeave,
}) => {
  if (phase === "error") {
    return (
      <div className="oauth-page">
        <Card className="oauth-card">
          <Result
            status="warning"
            title="This authorization request can't be completed"
            subTitle={errorMessage}
            extra={
              errorRedirectUrl ? (
                <Button
                  type="primary"
                  onClick={() => onReturn(errorRedirectUrl)}
                >
                  Return to {hostOf(errorRedirectUrl)}
                </Button>
              ) : (
                <Button type="primary" onClick={onLeave}>
                  Go to dashboard
                </Button>
              )
            }
          />
        </Card>
      </div>
    );
  }

  if (phase !== "consent" || !info) {
    return (
      <div className="oauth-page">
        <Card className="oauth-card oauth-card-waiting">
          <Spin size="large" />
          <p className="oauth-waiting-text">
            {phase === "redirecting" && info
              ? `Returning you to ${info.client_name}…`
              : "Checking the authorization request…"}
          </p>
        </Card>
      </div>
    );
  }

  return (
    <div className="oauth-page">
      <Card className="oauth-card">
        <div className="oauth-parties">
          <AppAvatar logoUrl={info.logo_url} />
          <SwapOutlined className="oauth-parties-arrow" />
          <Avatar size={64} className="oauth-crapi-avatar">
            crAPI
          </Avatar>
        </div>

        <h1 className="oauth-title">
          <strong>{info.client_name}</strong> wants to access your crAPI account
        </h1>

        <div className="oauth-user">
          <UserOutlined /> Signed in as <strong>{userName || userEmail}</strong>
          {userName && userEmail ? ` (${userEmail})` : ""}
        </div>

        <p className="oauth-scope-intro">
          This will allow {info.client_name} to:
        </p>
        <ul className="oauth-scope-list">
          {info.scopes.map((scope) => (
            <li key={scope} className="oauth-scope-item">
              <CheckCircleFilled className="oauth-scope-icon" />
              <span>
                {describeScope(scope)}
                <code className="oauth-scope-code">{scope}</code>
              </span>
            </li>
          ))}
        </ul>

        <p className="oauth-redirect-note">
          You will be sent back to <strong>{hostOf(info.redirect_uri)}</strong>.
        </p>

        <div className="oauth-actions">
          <Button size="large" onClick={onDeny} className="oauth-deny-btn">
            Deny
          </Button>
          <Button
            size="large"
            type="primary"
            onClick={onApprove}
            className="oauth-allow-btn"
          >
            Allow
          </Button>
        </div>

        <p className="oauth-footnote">
          You can disconnect {info.client_name} at any time from your profile
          page.
        </p>
      </Card>
    </div>
  );
};

export default OAuthAuthorize;
