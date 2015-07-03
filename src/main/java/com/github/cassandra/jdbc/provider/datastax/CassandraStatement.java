/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package com.github.cassandra.jdbc.provider.datastax;

import static com.github.cassandra.jdbc.CassandraUtils.EMPTY_STRING;

import java.sql.Connection;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.ExecutionInfo;
import com.datastax.driver.core.QueryTrace;
import com.datastax.driver.core.QueryTrace.Event;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.SimpleStatement;
import com.github.cassandra.jdbc.BaseCassandraStatement;
import com.github.cassandra.jdbc.CassandraErrors;

/**
 * This is a statement implementation built on top of DataStax Java driver.
 *
 * @author Zhichun Wu
 */
public class CassandraStatement extends BaseCassandraStatement {
	private static final Logger logger = LoggerFactory
			.getLogger(CassandraStatement.class);

	private CassandraResultSet _currentResultSet;
	private com.datastax.driver.core.Session _session;

	protected CassandraStatement(CassandraConnection conn,
			com.datastax.driver.core.Session session) {
		super(conn);
		_session = session;
	}

	public boolean execute(String sql) throws SQLException {
		ResultSet rs = executeCql(sql);
		replaceCurrentResultSet(rs);
		return true;
	}

	public boolean execute(String sql, int autoGeneratedKeys)
			throws SQLException {
		throw CassandraErrors.notSupportedException();
	}

	public boolean execute(String sql, int[] columnIndexes) throws SQLException {
		throw CassandraErrors.notSupportedException();
	}

	public boolean execute(String sql, String[] columnNames)
			throws SQLException {
		throw CassandraErrors.notSupportedException();
	}

	protected ResultSet executeCql(String cql) throws SQLException {
		if (logger.isDebugEnabled()) {
			logger.debug(new StringBuilder(
					"Trying to execute the following CQL:\n").append(cql)
					.toString());
		}

		if (_session != null && !_session.isClosed()) {
			boolean queryTrace = false;

			Connection c = this.getConnection();
			if (c != null) {
				cql = c.nativeSQL(cql);
			}

			SimpleStatement ss = new SimpleStatement(cql);
			ss.setFetchSize(this.getFetchSize());
			if (c instanceof CassandraConnection) {
				CassandraConnection cc = (CassandraConnection) c;
				queryTrace = cc.queryTraceEnabled;

				if (queryTrace) {
					ss.enableTracing();
				}
			}

			ResultSet rs = _session.execute(ss);
			if (logger.isDebugEnabled()) {
				for (ExecutionInfo info : rs.getAllExecutionInfo()) {
					logger.debug(new StringBuilder("Execution Info:\n")
							.append("* schema aggrement: ")
							.append(info.isSchemaInAgreement())
							.append("\n* achieved consistency level: ")
							.append(info.getAchievedConsistencyLevel())
							.append("\n* queried host: ")
							.append(info.getQueriedHost())
							.append("\n* tried hosts: ")
							.append(info.getTriedHosts())
							.append("\n* paging state: ")
							.append(info.getPagingState()).toString());

					QueryTrace q = info.getQueryTrace();
					if (queryTrace && q != null) {
						StringBuilder trace = new StringBuilder();

						trace.append("Query Trace: \n[ id=")
								.append(q.getTraceId())
								.append(", coordinator=")
								.append(q.getCoordinator())
								.append(", requestType=")
								.append(q.getRequestType())
								.append(", startAt=")
								.append(new java.sql.Timestamp(q.getStartedAt()))
								.append(", duration=")
								.append(q.getDurationMicros())
								.append("(microseconds), params=")
								.append(q.getParameters()).append(" ]");

						for (Event e : q.getEvents()) {
							trace.append("\n* event=[")
									.append(e.getDescription())
									.append("], location=[")
									.append(e.getThreadName())
									.append("@")
									.append(e.getSource())
									.append("], time=[")
									.append(new java.sql.Timestamp(e
											.getTimestamp()))
									.append("], elapsed=[")
									.append(e.getSourceElapsedMicros())
									.append("(microseconds)]");
						}

						logger.debug(trace.toString());
					}
				}

				logger.debug(new StringBuilder(
						"Executed successfully with results: ").append(
						!rs.isExhausted()).toString());
			}

			return rs;
		}

		throw CassandraErrors.statementClosedException();
	}

	public java.sql.ResultSet executeQuery(String sql) throws SQLException {
		ResultSet resultSet = executeCql(sql);
		replaceCurrentResultSet(resultSet);
		return _currentResultSet;
	}

	public int executeUpdate(String sql) throws SQLException {
		ResultSet resultSet = executeCql(sql);
		replaceCurrentResultSet(resultSet);
		return 0;
	}

	public int executeUpdate(String sql, int autoGeneratedKeys)
			throws SQLException {
		throw CassandraErrors.notSupportedException();
	}

	public int executeUpdate(String sql, int[] columnIndexes)
			throws SQLException {
		throw CassandraErrors.notSupportedException();
	}

	public int executeUpdate(String sql, String[] columnNames)
			throws SQLException {
		throw CassandraErrors.notSupportedException();
	}

	public java.sql.ResultSet getResultSet() throws SQLException {
		return _currentResultSet;
	}

	public int getUpdateCount() throws SQLException {
		return -1;
	}

	protected void replaceCurrentResultSet(ResultSet resultSet) {
		if (_currentResultSet != null) {
			try {
				if (!_currentResultSet.isClosed()) {
					_currentResultSet.close();
				}
			} catch (Throwable t) {
				if (logger.isWarnEnabled()) {
					logger.warn("Not able to close the old result set: "
							+ _currentResultSet, t);
				}
			}
		}

		_currentResultSet = new CassandraResultSet(this, resultSet);
	}

	@Override
	protected SQLException tryClose() {
		try {
			if (_currentResultSet != null && !_currentResultSet.isClosed()) {
				_currentResultSet.close();
			}
		} catch (Throwable t) {
			if (logger.isWarnEnabled()) {
				logger.warn("Not able to close the current result set: "
						+ _currentResultSet, t);
			}
		} finally {
			_currentResultSet = null;
		}

		SQLException e = null;
		try {
			if (_session != null && !_session.isClosed()) {
				_session.close();
				_session = null;
			}
		} catch (Throwable t) {
			if (logger.isWarnEnabled()) {
				logger.warn("Not able to close this statement: " + this, t);
			}
			e = new SQLException(t);
		} finally {
			_session = null;
		}

		return e;
	}

	@Override
	protected Object unwrap() {
		return _session;
	}

	@Override
	protected void validateState() throws SQLException {
		super.validateState();

		if (_session == null || _session.isClosed()) {
			_session = null;
			throw CassandraErrors.statementClosedException();
		}
	}
}
