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

import "./connectedApps.css";
import React from "react";
import { Avatar, Button, Card, List, Popconfirm, Tag, Tooltip } from "antd";
import { AppstoreOutlined, DisconnectOutlined } from "@ant-design/icons";
import { formatDateFromIso } from "../../utils";
import { describeScope } from "../oauthAuthorize/oauthAuthorize";

export interface ConnectedApp {
  client_id: string;
  client_name: string;
  logo_url?: string;
  scopes: string[];
  granted_at: string;
}

interface ConnectedAppsProps {
  apps: ConnectedApp[];
  loaded: boolean;
  onDisconnect: (clientId: string) => void;
}

/** Profile-page panel listing the third-party apps the user has authorized. */
const ConnectedApps: React.FC<ConnectedAppsProps> = ({
  apps,
  loaded,
  onDisconnect,
}) => (
  <Card className="video-section-card connected-apps-card" hoverable>
    <div className="connected-apps-header">
      <h2 className="video-title">Connected Apps</h2>
      <p className="video-subtitle">
        Apps you have allowed to use your crAPI account
      </p>
    </div>
    <List
      className="connected-apps-list"
      dataSource={apps}
      loading={!loaded}
      locale={{ emptyText: "No apps are connected to your account." }}
      renderItem={(app) => (
        <List.Item
          actions={[
            <Popconfirm
              key="disconnect"
              title={`Disconnect ${app.client_name}?`}
              description="It loses access to your account straight away."
              okText="Disconnect"
              okButtonProps={{ danger: true }}
              onConfirm={() => onDisconnect(app.client_id)}
            >
              <Button danger icon={<DisconnectOutlined />}>
                Disconnect
              </Button>
            </Popconfirm>,
          ]}
        >
          <List.Item.Meta
            avatar={
              <Avatar
                size={48}
                src={app.logo_url}
                icon={<AppstoreOutlined />}
                className="connected-app-avatar"
              />
            }
            title={app.client_name}
            description={
              <div>
                <div className="connected-app-scopes">
                  {app.scopes.map((scope) => (
                    <Tooltip key={scope} title={describeScope(scope)}>
                      <Tag color="purple">{scope}</Tag>
                    </Tooltip>
                  ))}
                </div>
                <div className="connected-app-date">
                  Authorized {formatDateFromIso(app.granted_at)}
                </div>
              </div>
            }
          />
        </List.Item>
      )}
    />
  </Card>
);

export default ConnectedApps;
