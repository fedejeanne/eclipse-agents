/*******************************************************************************
 * Copyright (c) 2025 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.agents.contexts.jdt;


import org.eclipse.agents.contexts.platform.resource.EditorAdapter;
import org.eclipse.agents.contexts.platform.resource.ResourceSchema.Editors;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

public class Tools {



	@McpTool(name = "sayHello", 
			description = "Say hello to the user with the given name", 
			annotations = @McpTool.McpAnnotations(
					title = "Say hello"))
	public String listEditors(@McpToolParam(
			description = "Name of the user") 
			String userName) {
		return "Hello " + userName;
	}

}