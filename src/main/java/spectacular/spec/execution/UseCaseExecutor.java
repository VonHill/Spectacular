package spectacular.spec.execution;


import groovy.lang.Closure;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import spectacular.data.model.*;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class UseCaseExecutor implements Executor<UseCase> {

    private static final String LOGGER_SEPARATOR = "-------------------------------";

    private static Log LOGGER = LogFactory.getLog(UseCaseExecutor.class);


    public void execute(Executable useCaseExecutable, Map<String, StepActionChain> stepActionChains, FixtureInventory fixtures, ExecutionResult result) throws SpectacularException {

        if (!useCaseExecutable.getExecutableType().equals(ExecutableType.USE_CASE)) {
            throw (new SpectacularException("Support for " + useCaseExecutable.getExecutableType() + " is not supported at this time."));
        }


        UseCase useCase = (UseCase) useCaseExecutable;
        UseCaseResult useCaseResult = (UseCaseResult) result;

        if (LOGGER.isInfoEnabled()) LOGGER.info("Executing use case:  " + useCase.getUseCaseTitle());
        SpectacularExecutionContext context = new SpectacularExecutionContext();

        if (LOGGER.isInfoEnabled()) LOGGER.info("\tExecuting Preconditions\n" + LOGGER_SEPARATOR);
        List<String> preconditionList = useCase.getPreconditions();
        for (String precondition : preconditionList) {
            if (LOGGER.isInfoEnabled()) LOGGER.info("\t\tPrecondition:  " + precondition);
            // TODO:  Implement preconditions
        }

        if (LOGGER.isInfoEnabled()) LOGGER.info("\tExecuting Primary Flow\n" + LOGGER_SEPARATOR);
        Flow primaryFlow = useCase.getPrimaryFlow();
        FlowResult flowResult = null;
        try {
            flowResult = executeFlow(primaryFlow, stepActionChains, fixtures, context);
            useCaseResult.setPrimaryFlow(flowResult);
        } catch (Exception e) {
            flowResult.setStatus(ExecutionResultStatus.FAIL);
            flowResult.setStatusCommentary("Error during execution:  " + e);
            useCaseResult.setPrimaryFlow(flowResult);
            throw (new SpectacularException(e));
        }


        if(LOGGER.isInfoEnabled()) LOGGER.info("\tExecuting Alternate Flows\n" + LOGGER_SEPARATOR);
        for(Flow altFlow : useCase.getAlternativeFlows()) {

            altFlow = flushAlternateFlow(primaryFlow, altFlow);
            FlowResult altFlowResult = new FlowResult(altFlow);

            try {
                altFlowResult = executeFlow(altFlow, stepActionChains, fixtures, context);
                useCaseResult.addAlternateFlow(altFlowResult);
            } catch(Exception e) {
                altFlowResult.setStatus(ExecutionResultStatus.FAIL);
                altFlowResult.setStatusCommentary("Error during execution:  " + e);
                useCaseResult.addAlternateFlow(altFlowResult);
                throw(new SpectacularException(e));
            }

        }



    }

    public Flow flushAlternateFlow(Flow primaryFlow, Flow altFlow) {

        List<Step> flushed = new LinkedList<Step>();
        List<Step> primarySteps = primaryFlow.getSteps();
        List<Step> altSteps = altFlow.getSteps();

        int firstStepId = Integer.parseInt(altSteps.get(0).getId());
        for(int i = 0 ; i < (firstStepId - 1) ; i++) {
            flushed.add(primarySteps.get(i));
        }

        flushed.addAll(altSteps);
        altFlow.setSteps(flushed);
        return(altFlow);

    }

    public FlowResult executeFlow(Flow flow, Map<String, StepActionChain> stepActionChains, FixtureInventory fixtureInventory, SpectacularExecutionContext context) throws Exception {

        if (LOGGER.isInfoEnabled()) LOGGER.info("Flow:  " + flow.getFlowTitle());
        FlowResult flowResult = new FlowResult(flow);
        flowResult.setStatus(ExecutionResultStatus.NOT_EXECUTED);

        boolean continueSteps = true;
        List<Step> stepList = flow.getSteps();
        for (Step step : stepList) {

            if(!continueSteps) {
                StepResult sr = new StepResult(step);
                sr.setStatus(ExecutionResultStatus.NOT_EXECUTED);
                flowResult.addStepResult(sr);
                continue;
            }

            if (LOGGER.isInfoEnabled()) LOGGER.info("  Step (" + step.getStepType() + "):  " + step.getStepTitle());
            StepResult stepResult = new StepResult(step);


            // look for step actions within chains
            StepActionChain chain = stepActionChains.get(step.getStepTitle());
            if (chain == null) {

                if (LOGGER.isInfoEnabled()) LOGGER.info("\t\tPENDING:  NO ACTION CHAINS FOR THIS STEP");
                stepResult.setStatus(ExecutionResultStatus.PENDING);
                stepResult.setStatusCommentary("Unable to find Actions matching this step.");

                flowResult.setStatus(ExecutionResultStatus.PENDING);
                continueSteps = false;


            }
            List<Action> actionList = stepActionChains.get(step.getStepTitle()).getActions();
            if (actionList == null) {

                if (LOGGER.isInfoEnabled()) LOGGER.info("\t\tPENDING:  NO ACTIONS FOR THIS CHAIN");
                stepResult.setStatus(ExecutionResultStatus.PENDING);
                stepResult.setStatusCommentary("Unable to find Action List matching this step.");

                flowResult.setStatus(ExecutionResultStatus.PENDING);
                continueSteps = false;

            }

            if (LOGGER.isInfoEnabled()) LOGGER.info("    Executing actions...");
            boolean continueActions = true;
            for (Action action : actionList) {

                if(!continueActions) {
                    ActionResult notPerformedResult = new ActionResult(action);
                    notPerformedResult.setStatus(ExecutionResultStatus.NOT_EXECUTED);
                    stepResult.addActionResult(notPerformedResult);
                    continue;
                }


                if (LOGGER.isInfoEnabled()) LOGGER.info("      **" + action.getActionText() + "**");
                ActionResult actionResult = executeFixtureForAction(action, fixtureInventory, context);
                stepResult.addActionResult(actionResult);

                if(!actionResult.getStatus().equals(ExecutionResultStatus.PASS)) {
                    stepResult.addActionResult(actionResult);
                    stepResult.setStatus(ExecutionResultStatus.FAIL);
                    continueActions = false;
                }

            }

            flowResult.addStepResult(stepResult);

        }


        return (flowResult);

    }

    public ActionResult executeFixtureForAction(Action action, FixtureInventory fixtureInventory, SpectacularExecutionContext context) {

        ActionResult result = new ActionResult(action);
        result.setStatus(ExecutionResultStatus.NOT_EXECUTED);

        // find fixture
        String actionText = action.getActionText();
        ExecutionInstance executionInstance = fixtureInventory.findExecutionInstanceForText(actionText);

        if (executionInstance == null) {
            result.setStatus(ExecutionResultStatus.PENDING);
            return (result);
        }

        try {

            // Object[] regexMatches = executionInstance.getRegexGroupMatches().toArray();
            int i = 1;
            Object[] regexMatches = new Object[executionInstance.getRegexGroupMatches().size() + 1];
            regexMatches[0] = context;
            for (String match : executionInstance.getRegexGroupMatches()) {
                regexMatches[i] = match;
                i++;
            }


            executionInstance.getClosure().call(regexMatches);


        } catch (Exception e) {
            if (LOGGER.isInfoEnabled()) LOGGER.info("Error while executing fixture.", e);
            result.setStatus(ExecutionResultStatus.FAIL);
            result.setStatusCommentary(e.toString());
            return (result);
        }


        result.setStatus(ExecutionResultStatus.PASS);
        return result;

    }


}
