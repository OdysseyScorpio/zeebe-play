let fastForwardMode = false;
let fastForwardContext;

function toggleFastForwardMode(event) {
  event?.preventDefault();
  $(`[data-bs-toggle="tooltip"]`).tooltip("hide");

  fastForwardMode = !fastForwardMode;
  document
    .getElementById("process-instance-fast-forward")
    ?.classList.toggle("fast-forward-active", fastForwardMode);

  if (fastForwardMode) {
    showNotificationSuccess(
      "fast-forward-mode",
      "Fast forward enabled",
      "Click the target element in the diagram."
    );
  }
}

function handleFastForwardElementClick(elementId) {
  if (!fastForwardMode) {
    return false;
  }

  fastForwardMode = false;
  document
    .getElementById("process-instance-fast-forward")
    ?.classList.remove("fast-forward-active");

  openFastForwardModal(elementId);
  return true;
}

function openFastForwardModal(targetElementId) {
  const targetElement = resolveFastForwardTargetElement(targetElementId);
  if (!targetElement) {
    return showNotificationFailure(
      "fast-forward-target",
      "Cannot fast forward",
      "Select a BPMN flow node."
    );
  }

  if (!processInstance || !isProcessInstanceActive(processInstance)) {
    return showNotificationFailure(
      "fast-forward-inactive",
      "Cannot fast forward",
      "The process instance is not active."
    );
  }

  const startElementIds = getFastForwardStartElementIds();
  const targetId = targetElement.id;

  if (startElementIds.includes(targetId)) {
    return showNotificationSuccess(
      "fast-forward-already-there",
      "Already at target",
      getFastForwardElementLabel(targetElement)
    );
  }

  const candidates = findFastForwardCandidates(startElementIds, targetId);
  if (candidates.length === 0) {
    return showNotificationFailure(
      "fast-forward-no-route",
      "No route found",
      "No route from the active element to the selected target was found."
    );
  }

  fastForwardContext = {
    targetId,
    candidates,
    selectedCandidateIndex: 0,
    gatewaySelections: {},
  };

  renderFastForwardModal();
  $("#fast-forward-modal").modal("show");
}

function renderFastForwardModal() {
  const context = fastForwardContext;
  const selectedCandidate = getSelectedFastForwardCandidate();
  const targetElement = elementRegistry.get(context.targetId);
  const candidateCount = context.candidates.length;

  document.getElementById("fast-forward-target-id").value = context.targetId;
  document.getElementById("fast-forward-target").textContent =
    getFastForwardElementLabel(targetElement);
  document.getElementById("fast-forward-summary").innerHTML =
    candidateCount > 3
      ? `${candidateCount} routes found. The simplest route is selected; adjust the gateway decisions below.`
      : `${candidateCount} route${candidateCount === 1 ? "" : "s"} found.`;

  renderFastForwardRoutesTable();
  renderFastForwardGatewayDecisions(selectedCandidate);
  renderFastForwardVariables(selectedCandidate);
}

function renderFastForwardRoutesTable() {
  const routesTable = document.getElementById("fast-forward-routes-table");
  routesTable.innerHTML = "";

  fastForwardContext.candidates.slice(0, 3).forEach((candidate, index) => {
    const row = document.createElement("tr");
    row.innerHTML = `
      <td>
        <input class="form-check-input fast-forward-route-choice" type="radio" name="fast-forward-route" />
      </td>
      <td class="fast-forward-route-label"></td>
      <td class="fast-forward-route-decisions"></td>
      <td class="fast-forward-route-steps"></td>`;

    const input = row.querySelector(".fast-forward-route-choice");
    input.checked = index === fastForwardContext.selectedCandidateIndex;
    input.addEventListener("change", () => {
      fastForwardContext.selectedCandidateIndex = index;
      fastForwardContext.gatewaySelections = {};
      renderFastForwardModal();
    });

    row.querySelector(".fast-forward-route-label").textContent =
      index === 0 ? "Simplest path" : `Path ${index + 1}`;
    row.querySelector(".fast-forward-route-decisions").textContent =
      formatFastForwardDecisions(candidate);
    row.querySelector(".fast-forward-route-steps").textContent =
      candidate.nodeIds.map(getFastForwardElementLabelById).join(" -> ");

    routesTable.appendChild(row);
  });
}

function renderFastForwardGatewayDecisions(candidate) {
  const table = document.getElementById("fast-forward-gateway-decisions-table");
  table.innerHTML = "";

  if (candidate.decisions.length === 0) {
    const row = document.createElement("tr");
    row.innerHTML =
      '<td colspan="4" class="text-muted">No gateway choices are needed.</td>';
    table.appendChild(row);
    return;
  }

  candidate.decisions.forEach((decision) => {
    const gatewayElement = elementRegistry.get(decision.gatewayId);
    const route = getFastForwardDecisionRoute(decision);
    const row = document.createElement("tr");
    row.innerHTML = `
      <td class="fast-forward-gateway"></td>
      <td class="fast-forward-path"></td>
      <td class="fast-forward-next-step"></td>
      <td><code class="fast-forward-condition"></code></td>`;

    row.querySelector(".fast-forward-gateway").textContent =
      getFastForwardElementLabel(gatewayElement);

    const options = getFastForwardDecisionOptions(decision.gatewayId);
    if (fastForwardContext.candidates.length > 3 && options.length > 1) {
      const select = document.createElement("select");
      select.classList.add("form-select", "form-select-sm");
      options.forEach((option) => {
        const optionElement = document.createElement("option");
        optionElement.value = option.flowId;
        optionElement.textContent = option.label;
        optionElement.selected = option.flowId === decision.flowId;
        select.appendChild(optionElement);
      });
      select.addEventListener("change", () => {
        fastForwardContext.gatewaySelections[decision.gatewayId] =
          select.value;
        selectFastForwardCandidateFromGatewayChoices();
        renderFastForwardModal();
      });
      row.querySelector(".fast-forward-path").appendChild(select);
    } else {
      row.querySelector(".fast-forward-path").textContent =
        route?.label || decision.flowId;
    }

    row.querySelector(".fast-forward-next-step").textContent =
      route?.targetLabel || getFastForwardElementLabelById(decision.targetId);
    row.querySelector(".fast-forward-condition").textContent =
      route?.isDefault ? "default" : route?.conditionExpression || "-";

    table.appendChild(row);
  });
}

function renderFastForwardVariables(candidate) {
  const variables = getFastForwardVariables(candidate);
  document.getElementById("fast-forward-variables-payload").value =
    JSON.stringify(variables, null, 2);
}

function confirmFastForwardModal() {
  const candidate = getSelectedFastForwardCandidate();
  const variables = document
    .getElementById("fast-forward-variables-payload")
    .value.trim() || "{}";

  let parsedVariables;
  try {
    parsedVariables = JSON.parse(variables);
    if (!parsedVariables || Array.isArray(parsedVariables)) {
      throw new Error("Variables must be a JSON object.");
    }
  } catch (e) {
    return showNotificationFailure(
      "fast-forward-invalid-json",
      "Invalid variables JSON",
      "<code>" + e.message + "</code>"
    );
  }

  $("#fast-forward-modal").modal("hide");
  executeFastForward(candidate, parsedVariables);
}

async function executeFastForward(candidate, variables) {
  const toastId = "fast-forward-" + getProcessInstanceKey();
  const processInstanceKey = getProcessInstanceKey();
  addFastForwardBlocker();

  try {
    for (const decision of candidate.decisions) {
      await sendSuppressGatewayPathControlAutoContinueRequest(
        processInstanceKey,
        decision.gatewayId
      );
    }

    for (let index = 0; index < candidate.nodeIds.length - 1; index++) {
      const elementId = candidate.nodeIds[index];
      const element = elementRegistry.get(elementId);

      if (isGatewayChoiceCandidate(element)) {
        const decision = candidate.decisions.find(
          (entry) => entry.gatewayId === elementId
        );
        if (!decision) {
          throw new Error(
            "No gateway decision found for " +
              getFastForwardElementLabel(element) +
              "."
          );
        }
        const jobKey = await waitForFastForwardGatewayJobKey(
          processInstanceKey,
          elementId
        );
        await completeFastForwardGateway(jobKey, decision, variables);
        continue;
      }

      if (isFastForwardCompletableElement(element)) {
        const jobKey = await waitForFastForwardJobKey(
          processInstanceKey,
          elementId
        );
        await completeFastForwardJob(jobKey, elementId);
        continue;
      }

      if (isFastForwardBlockingElement(element)) {
        throw new Error(
          "Fast forward needs manual handling at " +
            getFastForwardElementLabel(element) +
            "."
        );
      }
    }

    showNotificationSuccess(
      toastId,
      "Fast forward complete",
      getFastForwardElementLabelById(candidate.targetId)
    );
    loadViewDebounced(300);
  } catch (e) {
    showNotificationFailure(
      toastId,
      "Fast forward stopped",
      "<code>" +
        (e?.message || "Could not reach the selected target.") +
        "</code>"
    );
  } finally {
    removeFastForwardBlocker();
  }
}

function completeFastForwardGateway(jobKey, decision, variables) {
  const route = getFastForwardDecisionRoute(decision);
  const gatewayVariables = getFastForwardVariablesForDecision(
    decision,
    variables
  );
  const serializedVariables = JSON.stringify(gatewayVariables);

  history.push({
    action: GATEWAY_PATH_CONTROL_ACTION,
    task: decision.gatewayId,
    gateway: decision.gatewayId,
    variables: serializedVariables,
    routeId: route?.id,
    routeLabel: route?.label,
    fastForward: true,
  });
  refreshHistory();

  return sendCompleteJobRequest(jobKey, serializedVariables);
}

function completeFastForwardJob(jobKey, elementId) {
  const variables = "{}";
  history.push({ action: "completeJob", task: elementId, variables });
  refreshHistory();

  return sendCompleteJobRequest(jobKey, variables);
}

function addFastForwardBlocker() {
  const blocker = document.createElement("div");
  blocker.setAttribute("id", "fast-forward-blocker");
  blocker.innerHTML =
    '<svg class="bi" style="fill: black;"><use xlink:href="/img/bootstrap-icons.svg#skip-forward"/></svg> Fast forwarding...';
  document.body.appendChild(blocker);
  document.body.style.overflow = "hidden";
  scrollTo({ top: 0, left: 0, behavior: "instant" });
  setTimeout(() => (blocker.style.opacity = 1));
}

function removeFastForwardBlocker() {
  document.getElementById("fast-forward-blocker")?.remove();
  document.body.style.overflow = "";
}

async function waitForFastForwardGatewayJobKey(processInstanceKey, elementId) {
  for (let attempt = 0; attempt < FAST_FORWARD_JOB_FETCH_ATTEMPTS; attempt++) {
    const response = await queryJobsByProcessInstance(processInstanceKey);
    const job = response.data.processInstance.jobs.find(
      (job) =>
        isGatewayChoiceJob(job) &&
        job.elementInstance.element.elementId === elementId &&
        job.state !== "COMPLETED"
    );

    if (job) {
      return job.key;
    }

    await waitForFastForwardRuntime();
  }

  throw new Error(
    "Gateway path control was not ready at " +
      getFastForwardElementLabelById(elementId) +
      "."
  );
}

async function waitForFastForwardJobKey(processInstanceKey, elementId) {
  for (let attempt = 0; attempt < FAST_FORWARD_JOB_FETCH_ATTEMPTS; attempt++) {
    const [userTaskResponse, jobResponse] = await Promise.all([
      queryUserTasksByProcessInstance(processInstanceKey),
      queryJobsByProcessInstance(processInstanceKey),
    ]);

    const userTask =
      userTaskResponse.data.processInstance.userTasks.nodes.find(
        (userTask) =>
          userTask.elementInstance.element.elementId === elementId &&
          userTask.state !== "COMPLETED"
      );

    if (userTask) {
      return userTask.key;
    }

    const job = jobResponse.data.processInstance.jobs.find(
      (job) =>
        job.elementInstance.element.elementId === elementId &&
        job.state !== "COMPLETED"
    );

    if (job) {
      return job.key;
    }

    await waitForFastForwardRuntime();
  }

  throw new Error(
    "No active job was found at " +
      getFastForwardElementLabelById(elementId) +
      "."
  );
}

function waitForFastForwardRuntime() {
  return new Promise((resolve) =>
    setTimeout(resolve, FAST_FORWARD_JOB_FETCH_INTERVAL_MS)
  );
}

function findFastForwardCandidates(startElementIds, targetElementId) {
  const candidates = [];

  startElementIds.forEach((startElementId) => {
    findFastForwardPaths(
      startElementId,
      targetElementId,
      {
        nodeIds: [startElementId],
        decisions: [],
      },
      candidates
    );
  });

  return candidates
    .sort(compareFastForwardCandidates)
    .slice(0, FAST_FORWARD_MAX_CANDIDATES);
}

function findFastForwardPaths(
  currentElementId,
  targetElementId,
  path,
  candidates,
  depth = 0,
  visited = new Set([currentElementId])
) {
  if (currentElementId === targetElementId) {
    candidates.push({
      ...path,
      targetId: targetElementId,
      score: scoreFastForwardPath(path),
    });
    return;
  }

  if (
    depth >= FAST_FORWARD_MAX_DEPTH ||
    candidates.length >= FAST_FORWARD_SEARCH_LIMIT
  ) {
    return;
  }

  const currentElement = elementRegistry.get(currentElementId);
  const outgoing = getFastForwardOutgoingTransitions(currentElement);

  outgoing.forEach((transition) => {
    const targetId = transition.targetId;
    const flowId = transition.flowId;
    if (!targetId || visited.has(targetId)) {
      return;
    }

    const nextPath = {
      nodeIds: [...path.nodeIds, targetId],
      decisions: [...path.decisions],
    };

    if (!transition.virtual && isGatewayChoiceCandidate(currentElement)) {
      nextPath.decisions.push({
        gatewayId: currentElementId,
        flowId,
        targetId,
      });
    }

    const nextVisited = new Set(visited);
    nextVisited.add(targetId);
    findFastForwardPaths(
      targetId,
      targetElementId,
      nextPath,
      candidates,
      depth + 1,
      nextVisited
    );
  });
}

function getFastForwardOutgoingTransitions(element) {
  if (!element) {
    return [];
  }

  const subprocessStarts = getFastForwardSubProcessStartEvents(element);
  if (subprocessStarts.length > 0) {
    return subprocessStarts.map((startEvent) => ({
      targetId: startEvent.id,
      flowId: element.id + ":enter:" + startEvent.id,
      virtual: true,
    }));
  }

  const transitions = getFastForwardSequenceFlowTransitions(element);

  if (isFastForwardSubProcessEndEvent(element)) {
    transitions.push(
      ...getFastForwardSequenceFlowTransitions(
        getFastForwardSubProcessParent(element),
        true
      )
    );
  }

  return transitions;
}

function getFastForwardSequenceFlowTransitions(element, virtual = false) {
  return (element?.businessObject?.outgoing || [])
    .map((flow) => {
      const sequenceFlow = elementRegistry.get(flow.id) || flow;
      return {
        targetId:
          flow.targetRef?.id ||
          sequenceFlow.target?.id ||
          sequenceFlow.businessObject?.targetRef?.id,
        flowId: flow.id || sequenceFlow.id,
        virtual,
      };
    })
    .filter((transition) => transition.targetId);
}

function getFastForwardSubProcessStartEvents(element) {
  if (!isFastForwardSubProcess(element)) {
    return [];
  }

  return getFastForwardChildElements(element).filter(
    (child) => child.type === "bpmn:StartEvent"
  );
}

function getFastForwardChildElements(element) {
  const childIds = new Set();

  (element.children || []).forEach((child) => childIds.add(child.id));
  (element.businessObject?.flowElements || []).forEach((flowElement) =>
    childIds.add(flowElement.id)
  );

  return [...childIds]
    .map((elementId) => elementRegistry.get(elementId))
    .filter(Boolean);
}

function isFastForwardSubProcessEndEvent(element) {
  return (
    element?.type === "bpmn:EndEvent" &&
    isFastForwardSubProcess(getFastForwardSubProcessParent(element))
  );
}

function getFastForwardSubProcessParent(element) {
  let parent = element?.parent;

  while (parent) {
    if (isFastForwardSubProcess(parent)) {
      return parent;
    }
    parent = parent.parent;
  }

  return null;
}

function isFastForwardSubProcess(element) {
  return (
    element?.type === "bpmn:SubProcess" ||
    element?.businessObject?.$type === "bpmn:SubProcess"
  );
}

function compareFastForwardCandidates(left, right) {
  return left.score - right.score ||
    left.decisions.length - right.decisions.length ||
    left.nodeIds.length - right.nodeIds.length;
}

function scoreFastForwardPath(path) {
  return path.decisions.length * 10 + path.nodeIds.length;
}

function getSelectedFastForwardCandidate() {
  return fastForwardContext.candidates[
    fastForwardContext.selectedCandidateIndex
  ];
}

function selectFastForwardCandidateFromGatewayChoices() {
  const selectedEntries = Object.entries(fastForwardContext.gatewaySelections);
  const index = fastForwardContext.candidates.findIndex((candidate) =>
    selectedEntries.every(([gatewayId, flowId]) =>
      candidate.decisions.some(
        (decision) =>
          decision.gatewayId === gatewayId && decision.flowId === flowId
      )
    )
  );

  if (index >= 0) {
    fastForwardContext.selectedCandidateIndex = index;
  }
}

function getFastForwardDecisionOptions(gatewayId) {
  const options = new Map();

  fastForwardContext.candidates.forEach((candidate) => {
    candidate.decisions
      .filter((decision) => decision.gatewayId === gatewayId)
      .forEach((decision) => {
        if (!options.has(decision.flowId)) {
          const route = getFastForwardDecisionRoute(decision);
          options.set(decision.flowId, {
            flowId: decision.flowId,
            label: route?.label || decision.flowId,
          });
        }
      });
  });

  return [...options.values()];
}

function getFastForwardDecisionRoute(decision) {
  const gatewayElement = elementRegistry.get(decision.gatewayId);
  return getGatewayChoiceRoutes(gatewayElement).find(
    (route) => route.id === decision.flowId
  );
}

function getFastForwardVariables(candidate) {
  const variables = {};

  candidate.decisions.forEach((decision) => {
    const route = getFastForwardDecisionRoute(decision);
    if (route?.hasSuggestion && hasGatewayChoiceVariables(route.suggestedVariables)) {
      mergeGatewayChoiceObjects(variables, route.suggestedVariables);
    }
  });

  return variables;
}

function getFastForwardVariablesForDecision(decision, variables) {
  const route = getFastForwardDecisionRoute(decision);
  const gatewayVariables = JSON.parse(JSON.stringify(variables || {}));

  if (route?.hasSuggestion && hasGatewayChoiceVariables(route.suggestedVariables)) {
    mergeGatewayChoiceObjects(gatewayVariables, route.suggestedVariables);
  }

  return gatewayVariables;
}

function formatFastForwardDecisions(candidate) {
  if (candidate.decisions.length === 0) {
    return "-";
  }

  return candidate.decisions
    .map((decision) => {
      const route = getFastForwardDecisionRoute(decision);
      return (
        getFastForwardElementLabelById(decision.gatewayId) +
        " -> " +
        (route?.label || decision.flowId)
      );
    })
    .join(", ");
}

function getFastForwardStartElementIds() {
  const activeElementIds = currentProcessInstanceElementSnapshot
    ?.activeElementInstances
    ?.map((elementInstance) => elementInstance.element)
    ?.filter((element) => element.bpmnElementType !== "PROCESS")
    ?.map((element) => element.elementId)
    ?.filter((elementId) => elementRegistry.get(elementId)) || [];

  if (activeElementIds.length > 0) {
    return [...new Set(removeFastForwardContainerElements(activeElementIds))];
  }

  return elementRegistry
    .filter((element) => element.type === "bpmn:StartEvent")
    .map((element) => element.id);
}

function removeFastForwardContainerElements(elementIds) {
  return elementIds.filter(
    (elementId) =>
      !elementIds.some(
        (otherElementId) =>
          otherElementId !== elementId &&
          isFastForwardDescendantOf(otherElementId, elementId)
      )
  );
}

function isFastForwardDescendantOf(elementId, parentElementId) {
  let parent = elementRegistry.get(elementId)?.parent;

  while (parent) {
    if (parent.id === parentElementId) {
      return true;
    }
    parent = parent.parent;
  }

  return false;
}

function resolveFastForwardTargetElement(elementId) {
  const element = elementRegistry.get(elementId);
  if (!element) {
    return null;
  }

  if (element.type === "bpmn:SequenceFlow") {
    const targetId =
      element.businessObject?.targetRef?.id || element.target?.id;
    return targetId ? elementRegistry.get(targetId) : null;
  }

  if (!element.businessObject?.outgoing && !element.businessObject?.incoming) {
    return null;
  }

  return element;
}

function isFastForwardCompletableElement(element) {
  return [
    "bpmn:BusinessRuleTask",
    "bpmn:CallActivity",
    "bpmn:ManualTask",
    "bpmn:ScriptTask",
    "bpmn:SendTask",
    "bpmn:ServiceTask",
    "bpmn:Task",
    "bpmn:UserTask",
  ].includes(element?.type);
}

function isFastForwardBlockingElement(element) {
  return [
    "bpmn:BoundaryEvent",
    "bpmn:EventBasedGateway",
    "bpmn:IntermediateCatchEvent",
    "bpmn:ReceiveTask",
  ].includes(element?.type);
}

function getFastForwardElementLabelById(elementId) {
  return getFastForwardElementLabel(elementRegistry.get(elementId));
}

function getFastForwardElementLabel(element) {
  if (!element) {
    return "-";
  }

  return (
    element.businessObject?.name ||
    element.businessObject?.id ||
    element.id ||
    "-"
  );
}

const FAST_FORWARD_MAX_DEPTH = 80;
const FAST_FORWARD_SEARCH_LIMIT = 50;
const FAST_FORWARD_MAX_CANDIDATES = 20;
const FAST_FORWARD_JOB_FETCH_ATTEMPTS = 24;
const FAST_FORWARD_JOB_FETCH_INTERVAL_MS = 250;
