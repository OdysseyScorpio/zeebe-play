let gatewayChoiceModalContext;
const GATEWAY_CHOICE_JOB_TYPE = "zeebe-play:gateway-choice";
const GATEWAY_PATH_CONTROL_ACTION = "gatewayPathControl";

function isGatewayChoiceJob(job) {
  return job?.jobType === GATEWAY_CHOICE_JOB_TYPE;
}

function getGatewayChoiceForIncident(incident) {
  if (
    !incident ||
    typeof elementRegistry === "undefined" ||
    !elementRegistry
  ) {
    return null;
  }

  const incidentElementId = incident.elementInstance?.element?.elementId;
  const incidentElement = elementRegistry.get(incidentElementId);
  const gatewayElement = findGatewayChoiceElement(incidentElement);

  if (!gatewayElement) {
    return null;
  }

  const routes = getGatewayChoiceRoutes(gatewayElement);
  if (!routes.some((route) => route.conditionExpression || route.isDefault)) {
    return null;
  }

  return {
    incident,
    gatewayElement,
    routes,
  };
}

function getGatewayChoiceForJob(job) {
  if (
    !job ||
    typeof elementRegistry === "undefined" ||
    !elementRegistry
  ) {
    return null;
  }

  const elementId = job.elementInstance?.element?.elementId;
  const element = elementRegistry.get(elementId);
  const gatewayElement = findGatewayChoiceElement(element);

  if (!gatewayElement) {
    return null;
  }

  const routes = getGatewayChoiceRoutes(gatewayElement);
  if (!routes.some((route) => route.conditionExpression || route.isDefault)) {
    return null;
  }

  return {
    job,
    gatewayElement,
    routes,
  };
}

function findGatewayChoiceElement(element) {
  if (!element) {
    return null;
  }

  if (isGatewayChoiceCandidate(element)) {
    return element;
  }

  const businessObject = element.businessObject || element;
  if (businessObject.$type === "bpmn:SequenceFlow") {
    const sourceId = businessObject.sourceRef?.id || element.source?.id;
    const sourceElement = sourceId ? elementRegistry.get(sourceId) : null;

    if (isGatewayChoiceCandidate(sourceElement)) {
      return sourceElement;
    }
  }

  return null;
}

function isGatewayChoiceCandidate(element) {
  if (!element) {
    return false;
  }

  const businessObject = element.businessObject || element;
  const gatewayTypes = ["bpmn:ExclusiveGateway", "bpmn:InclusiveGateway"];
  const outgoing = businessObject.outgoing || [];

  return (
    gatewayTypes.includes(businessObject.$type) &&
    outgoing.length > 1 &&
    outgoing.some(
      (flow) =>
        getGatewayChoiceConditionExpression(flow) ||
        isGatewayChoiceDefaultFlow(businessObject, flow)
    )
  );
}

function getGatewayChoiceRoutes(gatewayElement) {
  const gateway = gatewayElement.businessObject || gatewayElement;
  const routes = (gateway.outgoing || []).map((flow) => {
    const sequenceFlow = elementRegistry.get(flow.id) || flow;
    const sequenceFlowBusinessObject =
      sequenceFlow.businessObject || sequenceFlow;
    const target =
      sequenceFlow.target?.businessObject ||
      sequenceFlowBusinessObject.targetRef;
    const isDefault = isGatewayChoiceDefaultFlow(gateway, sequenceFlow);
    const conditionExpression =
      getGatewayChoiceConditionExpression(sequenceFlow);

    return {
      id: sequenceFlowBusinessObject.id || sequenceFlow.id,
      label:
        sequenceFlowBusinessObject.name ||
        sequenceFlowBusinessObject.id ||
        sequenceFlow.id,
      targetId: target?.id || sequenceFlow.target?.id || "",
      targetLabel:
        target?.name || target?.id || sequenceFlow.target?.id || "-",
      isDefault,
      conditionExpression,
      suggestedVariables: null,
    };
  });

  routes.forEach((route) => {
    route.suggestedVariables = route.isDefault
      ? suggestGatewayChoiceVariablesForDefault(routes)
      : suggestGatewayChoiceVariables(route.conditionExpression);
    route.hasSuggestion = route.suggestedVariables !== null;
  });

  return routes;
}

function isGatewayChoiceDefaultFlow(gateway, sequenceFlow) {
  const defaultFlowId = gateway.default?.id;
  const sequenceFlowId =
    sequenceFlow.businessObject?.id || sequenceFlow.id || sequenceFlow.$attrs?.id;

  return Boolean(defaultFlowId && defaultFlowId === sequenceFlowId);
}

function getGatewayChoiceConditionExpression(sequenceFlow) {
  const conditionExpression =
    sequenceFlow.businessObject?.conditionExpression ||
    sequenceFlow.conditionExpression;

  return (
    conditionExpression?.body ||
    conditionExpression?.textContent ||
    conditionExpression?.$body ||
    ""
  ).trim();
}

async function openGatewayChoiceModal(incidentKey, jobKey) {
  const processInstanceKey = getProcessInstanceKey();

  document.querySelector("#new-toast-new-incident .btn-close")?.click();

  const [incidentResponse, variableResponse] = await Promise.all([
    queryIncidentsByProcessInstance(processInstanceKey),
    queryVariablesByProcessInstance(processInstanceKey),
  ]);

  const incident = incidentResponse.data.processInstance.incidents.find(
    ({ key }) => String(key) === String(incidentKey)
  );
  const gatewayChoice = getGatewayChoiceForIncident(incident);

  if (!gatewayChoice) {
    return openResolveIncidentModal(incidentKey, jobKey);
  }

  const gateway = gatewayChoice.gatewayElement.businessObject;
  const variables = variableResponse.data.processInstance.variables;

  gatewayChoiceModalContext = {
    incident,
    gatewayChoice,
    variables,
    selectedRoute: null,
  };

  document.getElementById("gateway-choice-incident-key").value = incidentKey;
  document.getElementById("gateway-choice-job-key").value = jobKey;
  document.getElementById("gateway-choice-element-id").value =
    gatewayChoice.gatewayElement.id;
  document.getElementById("gateway-choice-gateway").textContent =
    gateway.name || gateway.id;
  document.getElementById("gateway-choice-message-label").textContent =
    "Gateway Path Control";
  document.getElementById("gateway-choice-incident-message").textContent =
    incident.errorMessage;
  document.getElementById("gateway-choice-confirm-button").textContent =
    "Set Variables and Resolve";
  document.getElementById("gateway-choice-variables-payload").value = "";

  renderGatewayChoiceRoutes(gatewayChoice.routes);
  renderGatewayChoiceVariables(variables);

  $("#gateway-choice-modal").modal("show");
}

async function openGatewayChoiceJobModal(jobKey) {
  const processInstanceKey = getProcessInstanceKey();

  const [jobResponse, variableResponse, gatewayPathControlStatus] = await Promise.all([
    queryJobsByProcessInstance(processInstanceKey),
    queryVariablesByProcessInstance(processInstanceKey),
    sendGetGatewayPathControlStatusRequest(jobKey).then(
      (status) => status,
      () => null
    ),
  ]);

  const job = jobResponse.data.processInstance.jobs.find(
    ({ key }) => String(key) === String(jobKey)
  );
  const gatewayChoice = getGatewayChoiceForJob(job);

  if (!gatewayChoice) {
    return showJobCompleteModal(jobKey, "complete", "");
  }

  const gateway = gatewayChoice.gatewayElement.businessObject;
  const variables = variableResponse.data.processInstance.variables;
  applyGatewayPathControlStatus(
    gatewayChoice.routes,
    gatewayPathControlStatus
  );

  gatewayChoiceModalContext = {
    job,
    gatewayChoice,
    variables,
    selectedRoute: null,
  };

  document.getElementById("gateway-choice-incident-key").value = "";
  document.getElementById("gateway-choice-job-key").value = jobKey;
  document.getElementById("gateway-choice-element-id").value =
    gatewayChoice.gatewayElement.id;
  document.getElementById("gateway-choice-gateway").textContent =
    gateway.name || gateway.id;
  document.getElementById("gateway-choice-message-label").textContent =
    "Gateway Path Control";
  document.getElementById("gateway-choice-incident-message").textContent =
    "The process is paused before this gateway. Choose a path, then continue.";
  document.getElementById("gateway-choice-confirm-button").textContent =
    "Set Variables and Continue";
  document.getElementById("gateway-choice-variables-payload").value = "";

  renderGatewayChoiceRoutes(gatewayChoice.routes);
  renderGatewayChoiceVariables(variables);

  $("#gateway-choice-modal").modal("show");
}

function renderGatewayChoiceRoutes(routes) {
  const table = document.getElementById("gateway-choice-routes-table");
  table.innerHTML = "";

  routes.forEach((route) => {
    const row = document.createElement("tr");
    row.innerHTML = `
      <td>
        <span class="gateway-choice-path-name"></span>
        <span class="badge bg-secondary gateway-choice-default-badge hidden">default</span>
      </td>
      <td class="gateway-choice-target">
        <span class="gateway-choice-target-from"></span>
        <svg class="bi" width="18" height="18" fill="black">
          <use xlink:href="/img/bootstrap-icons.svg#arrow-right"/>
        </svg>
        <span class="gateway-choice-target-to"></span>
      </td>
      <td><code class="gateway-choice-condition"></code></td>
      <td><code class="gateway-choice-suggestion"></code></td>
      <td>
        <button type="button" class="btn btn-sm btn-outline-primary gateway-choice-use-route">
          Use
        </button>
      </td>`;

    row.querySelector(".gateway-choice-path-name").textContent = route.label;
    row.querySelector(".gateway-choice-target-from").textContent = route.label;
    row.querySelector(".gateway-choice-target-to").textContent =
      route.targetLabel;

    if (route.isDefault) {
      row
        .querySelector(".gateway-choice-default-badge")
        .classList.remove("hidden");
    }

    row.querySelector(".gateway-choice-condition").textContent = route.isDefault
      ? "default"
      : route.conditionExpression || "-";

    const useButton = row.querySelector(".gateway-choice-use-route");
    if (route.suggestionAlreadySet) {
      row.querySelector(".gateway-choice-suggestion").textContent =
        "Already set";
      useButton.disabled = true;
    } else if (route.hasSuggestion) {
      row.querySelector(".gateway-choice-suggestion").textContent =
        hasGatewayChoiceVariables(route.suggestedVariables)
          ? JSON.stringify(route.suggestedVariables, null, 2)
          : "Leave unset";
      useButton.addEventListener("click", () => {
        gatewayChoiceModalContext.selectedRoute = route;
        mergeGatewayChoiceVariables(route.suggestedVariables);
      });
    } else {
      row.querySelector(".gateway-choice-suggestion").textContent = "-";
      useButton.disabled = true;
    }

    table.appendChild(row);
  });
}

function renderGatewayChoiceVariables(variables) {
  const variablesSection = document.getElementById(
    "gateway-choice-variables-section"
  );
  if (variables.length === 0) {
    variablesSection.classList.add("hidden");
  } else {
    variablesSection.classList.remove("hidden");
  }

  const variablesTable = document.getElementById(
    "gateway-choice-variables-table"
  );
  variablesTable.innerHTML = "";

  variables.forEach((variable) => {
    const row = document.createElement("tr");
    row.innerHTML = `
      <td class="name"></td>
      <td class="value"><code></code></td>
      <td class="scope"><span class="badge"></span></td>
      <td class="scopeElement"></td>
      <td class="scopeKey"></td>
      <td>
        <button type="button" class="btn btn-sm btn-outline-primary gateway-choice-use-variable">
          Use
        </button>
      </td>`;

    row.querySelector(".name").textContent = variable.name;
    row.querySelector(".value code").textContent = variable.value;

    if (variable.scope.element.bpmnElementType === "PROCESS") {
      row.querySelector(".badge").textContent = "global";
      row.querySelector(".badge").classList.add("bg-primary");
    } else {
      row.querySelector(".badge").textContent = "local";
      row.querySelector(".badge").classList.add("bg-secondary");
    }

    row.querySelector(".scopeElement").innerHTML = formatBpmnElementInstance(
      variable.scope.element
    );
    row.querySelector(".scopeElement button")?.remove();
    row.querySelector(".scopeKey").textContent = variable.scope.key;

    row
      .querySelector(".gateway-choice-use-variable")
      .addEventListener("click", () => {
        mergeGatewayChoiceVariables({
          [variable.name]: parseGatewayChoiceVariableValue(variable.value),
        });
      });

    variablesTable.appendChild(row);
  });
}

function confirmGatewayChoiceModal() {
  const incidentKey = document.getElementById(
    "gateway-choice-incident-key"
  ).value;
  const jobKey = document.getElementById("gateway-choice-job-key").value;
  const variablesInput = document
    .getElementById("gateway-choice-variables-payload")
    .value.trim();
  const variables = variablesInput || "{}";

  let parsedVariables;
  try {
    parsedVariables = JSON.parse(variables);
    if (!parsedVariables || Array.isArray(parsedVariables)) {
      throw new Error("Variables must be a JSON object.");
    }
  } catch (e) {
    return showNotificationFailure(
      "gateway-choice-invalid-json",
      "Invalid variables JSON",
      "<code>" + e.message + "</code>"
    );
  }

  $("#gateway-choice-modal").modal("hide");

  if (!incidentKey && jobKey) {
    return continueGatewayPathControl(jobKey, variables);
  }

  if (Object.keys(parsedVariables).length === 0) {
    return resolveIncident(incidentKey, jobKey);
  }

  history.push({
    action: "setVariables",
    variables,
  });
  refreshHistory();

  sendSetVariablesRequest(getProcessInstanceKey(), getProcessInstanceKey(), variables)
    .done((key) => {
      showNotificationSuccess(
        "gateway-choice-set-variables-" + key,
        "Variables set successfully"
      );

      loadVariablesOfProcessInstance();
      resolveIncident(incidentKey, jobKey);
    })
    .fail(
      showFailure(
        "gateway-choice-set-variables",
        "Failed to set gateway variables."
      )
    );
}

function continueGatewayPathControl(jobKey, variables) {
  const toastId = "gateway-choice-job-" + jobKey;
  const task = jobKeyToElementIdMapping[jobKey];
  const selectedRoute = gatewayChoiceModalContext?.selectedRoute;

  history.push({
    action: GATEWAY_PATH_CONTROL_ACTION,
    task,
    gateway: task,
    variables,
    routeId: selectedRoute?.id,
    routeLabel: selectedRoute?.label,
  });
  refreshHistory();

  sendCompleteJobRequest(jobKey, variables)
    .done(() => {
      showNotificationSuccess(
        toastId,
        "Gateway path controlled",
        getTaskNameByJobKey(jobKey)
      );
    })
    .fail(showFailure(toastId, "Failed to continue gateway."));
}

function mergeGatewayChoiceVariables(variables) {
  const payload = document.getElementById("gateway-choice-variables-payload");
  let currentVariables = {};

  try {
    currentVariables = JSON.parse(payload.value || "{}");
    if (!currentVariables || Array.isArray(currentVariables)) {
      currentVariables = {};
    }
  } catch (e) {
    currentVariables = {};
  }

  payload.value = JSON.stringify(
    mergeGatewayChoiceObjects(currentVariables, variables),
    null,
    2
  );
}

function mergeGatewayChoiceObjects(target, source) {
  Object.keys(source).forEach((key) => {
    if (
      isGatewayChoicePlainObject(target[key]) &&
      isGatewayChoicePlainObject(source[key])
    ) {
      mergeGatewayChoiceObjects(target[key], source[key]);
    } else {
      target[key] = source[key];
    }
  });

  return target;
}

function isGatewayChoicePlainObject(value) {
  return (
    value !== null &&
    typeof value === "object" &&
    !Array.isArray(value)
  );
}

function hasGatewayChoiceVariables(variables) {
  return (
    variables &&
    typeof variables === "object" &&
    Object.keys(variables).length > 0
  );
}

function applyGatewayPathControlStatus(routes, status) {
  if (
    !status?.inspected ||
    status.autoContinueSuppressed ||
    !Array.isArray(status.missingVariables)
  ) {
    return;
  }

  const missingVariables = new Set(status.missingVariables);
  routes.forEach((route) => {
    if (!route.hasSuggestion) {
      return;
    }

    const originalVariables = route.suggestedVariables;
    if (!hasGatewayChoiceVariables(originalVariables)) {
      return;
    }

    const filteredVariables = filterGatewayChoiceVariablesByMissingPaths(
      originalVariables,
      missingVariables
    );

    if (hasGatewayChoiceVariables(filteredVariables)) {
      route.suggestedVariables = filteredVariables;
    } else {
      route.suggestedVariables = {};
      route.hasSuggestion = false;
      route.suggestionAlreadySet = true;
    }
  });
}

function filterGatewayChoiceVariablesByMissingPaths(variables, missingVariables) {
  const filteredVariables = {};

  Object.entries(variables).forEach(([key, value]) => {
    const path = key;

    if (missingVariables.has(path)) {
      filteredVariables[key] = value;
      return;
    }

    if (isGatewayChoicePlainObject(value)) {
      const nestedMissingVariables = new Set(
        [...missingVariables]
          .filter((missingVariable) => missingVariable.startsWith(path + "."))
          .map((missingVariable) => missingVariable.substring(path.length + 1))
      );
      const filteredNestedVariables =
        filterGatewayChoiceVariablesByMissingPaths(
          value,
          nestedMissingVariables
        );

      if (hasGatewayChoiceVariables(filteredNestedVariables)) {
        filteredVariables[key] = filteredNestedVariables;
      }
    }
  });

  return filteredVariables;
}

function parseGatewayChoiceVariableValue(value) {
  try {
    return JSON.parse(value);
  } catch (e) {
    return value;
  }
}

function suggestGatewayChoiceVariables(conditionExpression) {
  const expression = normalizeGatewayChoiceExpression(conditionExpression);
  if (!expression || /\s+or\s+|\|\|/i.test(expression)) {
    return null;
  }

  const parts = expression.split(/\s+(?:and|&&)\s+/i);
  const suggestedVariables = {};

  for (const part of parts) {
    const suggestion = suggestGatewayChoiceVariable(part);
    if (!suggestion) {
      return null;
    }

    if (suggestion.variables) {
      mergeGatewayChoiceObjects(suggestedVariables, suggestion.variables);
    } else {
      assignGatewayChoiceVariable(
        suggestedVariables,
        suggestion.variableName,
        suggestion.value
      );
    }
  }

  return suggestedVariables;
}

function suggestGatewayChoiceVariablesForDefault(routes) {
  const conditionRoutes = routes.filter(
    (route) => !route.isDefault && route.conditionExpression
  );
  const comparisons = conditionRoutes
    .map((route) =>
      parseGatewayChoiceComparison(
        normalizeGatewayChoiceExpression(route.conditionExpression)
      )
    )
    .filter(Boolean);

  if (
    comparisons.length === 0 ||
    comparisons.length !== conditionRoutes.length ||
    !comparisons.every((comparison) =>
      ["=", "=="].includes(comparison.operator)
    )
  ) {
    return null;
  }

  const variableName = comparisons[0].variableName;
  if (!comparisons.every((comparison) => comparison.variableName === variableName)) {
    return null;
  }

  const values = comparisons.map((comparison) => comparison.value);
  const suggestedVariables = {};

  if (values.every((value) => typeof value === "string")) {
    assignGatewayChoiceVariable(suggestedVariables, variableName, "other");
    return suggestedVariables;
  }

  if (
    values.length === 1 &&
    values.every((value) => typeof value === "boolean")
  ) {
    assignGatewayChoiceVariable(suggestedVariables, variableName, !values[0]);
    return suggestedVariables;
  }

  return null;
}

function suggestGatewayChoiceVariable(expression) {
  const normalizedExpression = stripGatewayChoiceParentheses(
    normalizeGatewayChoiceExpression(expression)
  );
  const definedCheck = parseGatewayChoiceDefinedCheck(normalizedExpression);
  if (definedCheck) {
    if (definedCheck.negated) {
      return { variables: {} };
    }

    return {
      variableName: definedCheck.variableName,
      value: "value",
    };
  }

  const negativeBoolean = /^not\s+([A-Za-z_][\w.]*)$/i.exec(
    normalizedExpression
  );
  if (negativeBoolean) {
    return {
      variableName: negativeBoolean[1],
      value: false,
    };
  }

  const positiveBoolean = /^([A-Za-z_][\w.]*)$/.exec(normalizedExpression);
  if (positiveBoolean) {
    return {
      variableName: positiveBoolean[1],
      value: true,
    };
  }

  const comparison = parseGatewayChoiceComparison(normalizedExpression);
  if (!comparison) {
    return null;
  }

  const { variableName, operator, value } = comparison;
  if (["=", "=="].includes(operator)) {
    return { variableName, value };
  }
  if (operator === "!=") {
    return {
      variableName,
      value: suggestGatewayChoiceDifferentValue(value),
    };
  }
  if (typeof value !== "number") {
    return null;
  }
  if (operator === ">") {
    return { variableName, value: value + 1 };
  }
  if (operator === ">=") {
    return { variableName, value };
  }
  if (operator === "<") {
    return { variableName, value: value - 1 };
  }
  if (operator === "<=") {
    return { variableName, value };
  }

  return null;
}

function parseGatewayChoiceDefinedCheck(expression) {
  const negativeMatch =
    /^not\s*\(?\s*is\s+defined\s*\(\s*([A-Za-z_][\w.]*)\s*\)\s*\)?$/i.exec(
      expression
    );
  if (negativeMatch) {
    return {
      variableName: negativeMatch[1],
      negated: true,
    };
  }

  const positiveMatch =
    /^is\s+defined\s*\(\s*([A-Za-z_][\w.]*)\s*\)$/i.exec(expression);
  if (positiveMatch) {
    return {
      variableName: positiveMatch[1],
      negated: false,
    };
  }

  return null;
}

function parseGatewayChoiceComparison(expression) {
  const normalizedExpression = stripGatewayChoiceParentheses(
    normalizeGatewayChoiceExpression(expression)
  );
  const leftComparison =
    /^([A-Za-z_][\w.]*)\s*(==|!=|<=|>=|=|<|>)\s*(.+)$/.exec(
      normalizedExpression
    );

  if (leftComparison) {
    const literal = parseGatewayChoiceLiteral(leftComparison[3]);
    if (!literal.parsed) {
      return null;
    }

    return {
      variableName: leftComparison[1],
      operator: leftComparison[2],
      value: literal.value,
    };
  }

  const rightComparison =
    /^(.+?)\s*(==|!=|<=|>=|=|<|>)\s*([A-Za-z_][\w.]*)$/.exec(
      normalizedExpression
    );

  if (!rightComparison) {
    return null;
  }

  const literal = parseGatewayChoiceLiteral(rightComparison[1]);
  if (!literal.parsed) {
    return null;
  }

  return {
    variableName: rightComparison[3],
    operator: reverseGatewayChoiceOperator(rightComparison[2]),
    value: literal.value,
  };
}

function reverseGatewayChoiceOperator(operator) {
  return {
    ">": "<",
    ">=": "<=",
    "<": ">",
    "<=": ">=",
    "=": "=",
    "==": "==",
    "!=": "!=",
  }[operator];
}

function parseGatewayChoiceLiteral(rawValue) {
  const value = rawValue.trim();

  if (/^".*"$/.test(value)) {
    try {
      return { parsed: true, value: JSON.parse(value) };
    } catch (e) {
      return { parsed: true, value: value.substring(1, value.length - 1) };
    }
  }

  if (/^'.*'$/.test(value)) {
    return { parsed: true, value: value.substring(1, value.length - 1) };
  }

  if (/^true$/i.test(value)) {
    return { parsed: true, value: true };
  }

  if (/^false$/i.test(value)) {
    return { parsed: true, value: false };
  }

  if (/^null$/i.test(value)) {
    return { parsed: true, value: null };
  }

  if (/^-?\d+(\.\d+)?$/.test(value)) {
    return { parsed: true, value: Number(value) };
  }

  return { parsed: false };
}

function suggestGatewayChoiceDifferentValue(value) {
  if (typeof value === "boolean") {
    return !value;
  }
  if (typeof value === "number") {
    return value + 1;
  }
  if (value === null) {
    return "value";
  }

  return "not " + value;
}

function assignGatewayChoiceVariable(target, variableName, value) {
  const parts = variableName.split(".");
  let current = target;

  parts.forEach((part, index) => {
    if (index === parts.length - 1) {
      current[part] = value;
      return;
    }

    if (!isGatewayChoicePlainObject(current[part])) {
      current[part] = {};
    }

    current = current[part];
  });
}

function normalizeGatewayChoiceExpression(expression) {
  let normalizedExpression = (expression || "").trim();

  if (
    normalizedExpression.startsWith("${") &&
    normalizedExpression.endsWith("}")
  ) {
    normalizedExpression = normalizedExpression
      .substring(2, normalizedExpression.length - 1)
      .trim();
  }

  if (normalizedExpression.startsWith("=")) {
    normalizedExpression = normalizedExpression.substring(1).trim();
  }

  return stripGatewayChoiceParentheses(normalizedExpression);
}

function stripGatewayChoiceParentheses(expression) {
  let strippedExpression = expression.trim();

  while (
    hasGatewayChoiceWrappingParentheses(strippedExpression)
  ) {
    strippedExpression = strippedExpression
      .substring(1, strippedExpression.length - 1)
      .trim();
  }

  return strippedExpression;
}

function hasGatewayChoiceWrappingParentheses(expression) {
  if (!expression.startsWith("(") || !expression.endsWith(")")) {
    return false;
  }

  let depth = 0;
  let quote = null;

  for (let index = 0; index < expression.length; index++) {
    const character = expression[index];
    const previousCharacter = expression[index - 1];

    if (quote) {
      if (character === quote && previousCharacter !== "\\") {
        quote = null;
      }
      continue;
    }

    if (character === '"' || character === "'") {
      quote = character;
      continue;
    }

    if (character === "(") {
      depth++;
    }

    if (character === ")") {
      depth--;
      if (depth === 0 && index < expression.length - 1) {
        return false;
      }
    }
  }

  return depth === 0;
}
