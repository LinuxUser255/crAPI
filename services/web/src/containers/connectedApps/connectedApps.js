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

import React, { useCallback, useEffect, useState } from "react";
import PropTypes from "prop-types";
import { connect } from "react-redux";
import { Modal, message } from "antd";
import ConnectedApps from "../../components/connectedApps/connectedApps";
import {
  getConnectedAppsAction,
  disconnectAppAction,
} from "../../actions/oauthActions";
import responseTypes from "../../constants/responseTypes";
import { FAILURE_MESSAGE } from "../../constants/messages";

const ConnectedAppsContainer = (props) => {
  const { accessToken, getConnectedApps, disconnectApp } = props;
  const [apps, setApps] = useState([]);
  const [loaded, setLoaded] = useState(false);

  const loadApps = useCallback(() => {
    getConnectedApps({
      accessToken,
      callback: (res, data) => {
        setLoaded(true);
        if (res === responseTypes.SUCCESS) {
          setApps(data);
        } else {
          Modal.error({ title: FAILURE_MESSAGE, content: data });
        }
      },
    });
  }, [accessToken, getConnectedApps]);

  useEffect(() => {
    loadApps();
  }, [loadApps]);

  const onDisconnect = (clientId) => {
    disconnectApp({
      accessToken,
      clientId,
      callback: (res, data) => {
        if (res === responseTypes.SUCCESS) {
          message.success(data);
          loadApps();
        } else {
          Modal.error({ title: FAILURE_MESSAGE, content: data });
        }
      },
    });
  };

  return (
    <ConnectedApps apps={apps} loaded={loaded} onDisconnect={onDisconnect} />
  );
};

const mapStateToProps = ({ userReducer: { accessToken } }) => {
  return { accessToken };
};

const mapDispatchToProps = {
  getConnectedApps: getConnectedAppsAction,
  disconnectApp: disconnectAppAction,
};

ConnectedAppsContainer.propTypes = {
  accessToken: PropTypes.string,
  getConnectedApps: PropTypes.func,
  disconnectApp: PropTypes.func,
};

export default connect(
  mapStateToProps,
  mapDispatchToProps,
)(ConnectedAppsContainer);
