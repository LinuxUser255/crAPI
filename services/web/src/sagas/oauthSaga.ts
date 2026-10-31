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

import { put, takeLatest } from "redux-saga/effects";
import { APIService, requestURLS } from "../constants/APIConstant";
import actionTypes from "../constants/actionTypes";
import responseTypes from "../constants/responseTypes";
import {
  OAUTH_REQUEST_FAILED,
  OAUTH_DECISION_FAILED,
  NO_CONNECTED_APPS,
  APP_DISCONNECTED,
  APP_NOT_DISCONNECTED,
} from "../constants/messages";
import MyAction from "../types/action";

interface ReceivedResponse extends Response {
  ok: boolean;
}

/** Reads a JSON body if there is one; error and 204 responses may have none. */
const readJson = (response: Response): Promise<any> =>
  response.text().then((text) => {
    if (!text) {
      return null;
    }
    try {
      return JSON.parse(text);
    } catch (e) {
      return null;
    }
  });

/**
 * Validates an authorization request and fetches its description (client, scopes) for the
 * consent screen. On failure the callback gets the error body, which carries a redirect_url when
 * the error should be reported back to the client application.
 * @payload { accessToken, params, callback }
 */
export function* getOAuthRequest(action: MyAction): Generator<any, void, any> {
  const { accessToken, params, callback } = action.payload;
  let receivedResponse: ReceivedResponse = {} as ReceivedResponse;
  try {
    yield put({ type: actionTypes.FETCHING_DATA });
    const getUrl =
      APIService.IDENTITY_SERVICE +
      requestURLS.OAUTH_AUTHORIZE +
      "?" +
      new URLSearchParams(params).toString();
    const responseJson = yield fetch(getUrl, {
      headers: { Authorization: `Bearer ${accessToken}` },
      method: "GET",
    }).then((response: Response) => {
      receivedResponse = response as ReceivedResponse;
      return readJson(response);
    });

    yield put({ type: actionTypes.FETCHED_DATA, payload: receivedResponse });
    if (receivedResponse.ok) {
      callback(responseTypes.SUCCESS, responseJson);
    } else {
      callback(
        responseTypes.FAILURE,
        responseJson || { error_description: OAUTH_REQUEST_FAILED },
      );
    }
  } catch (e) {
    yield put({ type: actionTypes.FETCHED_DATA, payload: receivedResponse });
    callback(responseTypes.FAILURE, {
      error_description: OAUTH_REQUEST_FAILED,
    });
  }
}

/**
 * Sends the user's approve/deny decision. Either way a successful round trip yields the
 * redirect_url to send the browser back to the client with.
 * @payload { accessToken, params, approved, callback }
 */
export function* decideOAuthRequest(
  action: MyAction,
): Generator<any, void, any> {
  const { accessToken, params, approved, callback } = action.payload;
  let receivedResponse: ReceivedResponse = {} as ReceivedResponse;
  try {
    yield put({ type: actionTypes.FETCHING_DATA });
    const postUrl = APIService.IDENTITY_SERVICE + requestURLS.OAUTH_AUTHORIZE;
    const responseJson = yield fetch(postUrl, {
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        Authorization: `Bearer ${accessToken}`,
      },
      method: "POST",
      body: new URLSearchParams({
        ...params,
        approved: String(approved),
      }).toString(),
    }).then((response: Response) => {
      receivedResponse = response as ReceivedResponse;
      return readJson(response);
    });

    yield put({ type: actionTypes.FETCHED_DATA, payload: receivedResponse });
    if (responseJson && responseJson.redirect_url) {
      // A denial is a 400 that still carries where to send the user.
      callback(responseTypes.REDIRECT, responseJson.redirect_url);
    } else {
      callback(
        responseTypes.FAILURE,
        (responseJson && responseJson.error_description) ||
          OAUTH_DECISION_FAILED,
      );
    }
  } catch (e) {
    yield put({ type: actionTypes.FETCHED_DATA, payload: receivedResponse });
    callback(responseTypes.FAILURE, OAUTH_DECISION_FAILED);
  }
}

/**
 * Lists the apps the user has authorized.
 * @payload { accessToken, callback }
 */
export function* getConnectedApps(action: MyAction): Generator<any, void, any> {
  const { accessToken, callback } = action.payload;
  let receivedResponse: ReceivedResponse = {} as ReceivedResponse;
  try {
    yield put({ type: actionTypes.FETCHING_DATA });
    const getUrl = APIService.IDENTITY_SERVICE + requestURLS.OAUTH_CONSENTS;
    const responseJson = yield fetch(getUrl, {
      headers: { Authorization: `Bearer ${accessToken}` },
      method: "GET",
    }).then((response: Response) => {
      receivedResponse = response as ReceivedResponse;
      return readJson(response);
    });

    yield put({ type: actionTypes.FETCHED_DATA, payload: receivedResponse });
    if (receivedResponse.ok) {
      callback(responseTypes.SUCCESS, responseJson || []);
    } else {
      callback(responseTypes.FAILURE, NO_CONNECTED_APPS);
    }
  } catch (e) {
    yield put({ type: actionTypes.FETCHED_DATA, payload: receivedResponse });
    callback(responseTypes.FAILURE, NO_CONNECTED_APPS);
  }
}

/**
 * Disconnects an app: removes the user's consent and revokes the app's tokens.
 * @payload { accessToken, clientId, callback }
 */
export function* disconnectApp(action: MyAction): Generator<any, void, any> {
  const { accessToken, clientId, callback } = action.payload;
  let receivedResponse: ReceivedResponse = {} as ReceivedResponse;
  try {
    yield put({ type: actionTypes.FETCHING_DATA });
    const deleteUrl =
      APIService.IDENTITY_SERVICE +
      requestURLS.OAUTH_CONSENT.replace(
        "<clientId>",
        encodeURIComponent(clientId),
      );
    yield fetch(deleteUrl, {
      headers: { Authorization: `Bearer ${accessToken}` },
      method: "DELETE",
    }).then((response: Response) => {
      receivedResponse = response as ReceivedResponse;
      return readJson(response);
    });

    yield put({ type: actionTypes.FETCHED_DATA, payload: receivedResponse });
    if (receivedResponse.ok) {
      callback(responseTypes.SUCCESS, APP_DISCONNECTED);
    } else {
      callback(responseTypes.FAILURE, APP_NOT_DISCONNECTED);
    }
  } catch (e) {
    yield put({ type: actionTypes.FETCHED_DATA, payload: receivedResponse });
    callback(responseTypes.FAILURE, APP_NOT_DISCONNECTED);
  }
}

export function* oauthActionWatcher(): Generator<any, void, any> {
  yield takeLatest(actionTypes.GET_OAUTH_REQUEST, getOAuthRequest);
  yield takeLatest(actionTypes.DECIDE_OAUTH_REQUEST, decideOAuthRequest);
  yield takeLatest(actionTypes.GET_CONNECTED_APPS, getConnectedApps);
  yield takeLatest(actionTypes.DISCONNECT_APP, disconnectApp);
}
