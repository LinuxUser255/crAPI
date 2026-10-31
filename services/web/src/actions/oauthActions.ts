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

import actionTypes from "../constants/actionTypes";

type Callback = (res: string, data?: any) => void;

/** The OAuth parameters a client sends the user to the consent page with. */
export interface OAuthRequestParams {
  response_type: string;
  client_id: string;
  redirect_uri: string;
  scope?: string;
  state?: string;
}

export const getOAuthRequestAction = ({
  accessToken,
  params,
  callback,
}: {
  accessToken: string;
  params: OAuthRequestParams;
  callback: Callback;
}) => {
  return {
    type: actionTypes.GET_OAUTH_REQUEST,
    payload: { accessToken, params, callback },
  };
};

export const decideOAuthRequestAction = ({
  accessToken,
  params,
  approved,
  callback,
}: {
  accessToken: string;
  params: OAuthRequestParams;
  approved: boolean;
  callback: Callback;
}) => {
  return {
    type: actionTypes.DECIDE_OAUTH_REQUEST,
    payload: { accessToken, params, approved, callback },
  };
};

export const getConnectedAppsAction = ({
  accessToken,
  callback,
}: {
  accessToken: string;
  callback: Callback;
}) => {
  return {
    type: actionTypes.GET_CONNECTED_APPS,
    payload: { accessToken, callback },
  };
};

export const disconnectAppAction = ({
  accessToken,
  clientId,
  callback,
}: {
  accessToken: string;
  clientId: string;
  callback: Callback;
}) => {
  return {
    type: actionTypes.DISCONNECT_APP,
    payload: { accessToken, clientId, callback },
  };
};
