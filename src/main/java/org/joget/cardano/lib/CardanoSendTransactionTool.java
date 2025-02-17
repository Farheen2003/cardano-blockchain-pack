package org.joget.cardano.lib;

import org.joget.cardano.service.PluginUtil;
import org.joget.cardano.service.BackendUtil;
import org.joget.cardano.service.TransactionUtil;
import java.math.BigDecimal;
import java.util.Map;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.service.WorkflowManager;
import org.springframework.context.ApplicationContext;
import org.joget.workflow.util.WorkflowUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.transaction.model.PaymentTransaction;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.helper.model.TransactionResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.model.TransactionContent;
import com.bloxbean.cardano.client.common.ADAConversionUtil;
import static com.bloxbean.cardano.client.common.CardanoConstants.LOVELACE;
import com.bloxbean.cardano.client.exception.AddressExcepion;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.transaction.model.TransactionDetailsParams;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.joget.apps.app.dao.DatalistDefinitionDao;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.DatalistDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.datalist.model.DataList;
import org.joget.apps.datalist.model.DataListBinder;
import org.joget.apps.datalist.model.DataListCollection;
import org.joget.apps.datalist.model.DataListColumn;
import org.joget.apps.datalist.service.DataListService;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.cardano.model.CardanoProcessToolAbstract;
import org.joget.cardano.service.ExplorerLinkUtil;
import org.joget.cardano.service.MetadataUtil;
import org.joget.cardano.service.TokenUtil;
import static org.joget.cardano.service.TransactionUtil.MAX_FEE_LIMIT;
import org.joget.commons.util.PluginThread;
import org.joget.plugin.base.PluginWebSupport;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.BeansException;

public class CardanoSendTransactionTool extends CardanoProcessToolAbstract implements PluginWebSupport {
    
    protected DataListBinder binder = null;
    
    AppService appService;
    AppDefinition appDef;
    WorkflowManager workflowManager;
    DataListService dataListService;
    
    @Override
    public String getName() {
        return "Cardano Send Transaction Tool";
    }

    @Override
    public String getDescription() {
        return "Send assets from one account to another on the Cardano blockchain, with optional transaction metadata.";
    }
    
    @Override
    public String getPropertyOptions() {
        String backendConfigs = PluginUtil.readGenericBackendConfigs(getClassName());
        String wfVarMappings = PluginUtil.readGenericWorkflowVariableMappings(getClassName());
        return AppUtil.readPluginResource(getClassName(), "/properties/CardanoSendTransactionTool.json", new String[]{backendConfigs, wfVarMappings}, true, PluginUtil.MESSAGE_PATH);
    }
    
    protected void initUtils(Map props) {
        ApplicationContext ac = AppUtil.getApplicationContext();
        
        appService = (AppService) ac.getBean("appService");
        appDef = (AppDefinition) props.get("appDef");
        workflowManager = (WorkflowManager) ac.getBean("workflowManager");
        dataListService = (DataListService) ac.getBean("dataListService");
    }
    
    @Override
    public boolean isInputDataValid(Map props, WorkflowAssignment wfAssignment) {
        initUtils(props);
        
        String formDefId = getPropertyString("formDefId");
        final String primaryKey = appService.getOriginProcessId(wfAssignment.getProcessId());
        
        FormRowSet rowSet = appService.loadFormData(appDef.getAppId(), appDef.getVersion().toString(), formDefId, primaryKey);
        
        if (rowSet == null || rowSet.isEmpty()) {
            LogUtil.warn(getClassName(), "Send transaction aborted. No record found with record ID '" + primaryKey + "' from this form '" + formDefId + "'");
            return false;
        }
        
        FormRow row = rowSet.get(0);
        
        final String senderAddress = row.getProperty(getPropertyString("senderAddress"));
        final String accountMnemonic = PluginUtil.decrypt(WorkflowUtil.processVariable(getPropertyString("accountMnemonic"), "", wfAssignment));
        
        final boolean isTest = BackendUtil.isTestnet(props);
        final Network network = BackendUtil.getNetwork(isTest);

        final Account senderAccount = new Account(network, accountMnemonic);
        
        if (!senderAddress.equals(senderAccount.baseAddress())) {
            LogUtil.warn(getClassName(), "Send transaction aborted. Sender account encountered invalid mnemonic phrase.");
            return false;
        }
        
        return true;
    }
    
    @Override
    public void initBackendServices(BackendService backendService) {
        blockService = backendService.getBlockService();
        transactionHelperService = backendService.getTransactionHelperService();
        feeCalculationService = backendService.getFeeCalculationService();
        transactionService = backendService.getTransactionService();
    }
    
    @Override
    public Object runTool(Map props, WorkflowAssignment wfAssignment) 
            throws ApiException, CborSerializationException, AddressExcepion {
        
        initUtils(props);
        
        String formDefId = getPropertyString("formDefId");
        final String primaryKey = appService.getOriginProcessId(wfAssignment.getProcessId());
        
        FormRowSet rowSet = appService.loadFormData(appDef.getAppId(), appDef.getVersion().toString(), formDefId, primaryKey);
        
        FormRow row = rowSet.get(0);
        
        final String accountMnemonic = PluginUtil.decrypt(WorkflowUtil.processVariable(getPropertyString("accountMnemonic"), "", wfAssignment));
        final String receiverAddress = row.getProperty(getPropertyString("receiverAddress"));
        final String nftReceiverAddress = row.getProperty(getPropertyString("nftReceiverAddress")); // separate property to workaround multi-condition in properties options
        final String amount = row.getProperty(getPropertyString("amount"));
        final boolean multipleReceiverMode = "true".equalsIgnoreCase(getPropertyString("multipleReceiverMode"));
        final boolean paymentUnitNft = "nft".equalsIgnoreCase(getPropertyString("paymentUnit"));
        
        final boolean isTest = BackendUtil.isTestnet(props);
        final Network network = BackendUtil.getNetwork(isTest);

        final Account senderAccount = new Account(network, accountMnemonic);

        List<PaymentTransaction> paymentList = new ArrayList<>();

        if (multipleReceiverMode) { // If enabled multi receiver mode
            //Consider pulling binder plugin/configs directly from user selected Datalist
            paymentList = getPaymentListFromBinderData(senderAccount);

            if (paymentList == null || paymentList.isEmpty()) {
                LogUtil.warn(getClassName(), "Send transaction aborted. No valid receiver records found from binder.");
                return null;
            }
        } else { // If not enabled multi receiver mode (single receiver only)
            String tempReceiverAddress;

            if (paymentUnitNft) {
                tempReceiverAddress = nftReceiverAddress;
            } else {
                tempReceiverAddress = receiverAddress;
            }

            PaymentTransaction paymentTransaction =
                PaymentTransaction.builder()
                        .sender(senderAccount)
                        .receiver(tempReceiverAddress)
                        .amount(getPaymentAmount(amount))
                        .unit(getPaymentUnit())
                        .build();

            paymentList.add(paymentTransaction);
        }

        long ttl = TransactionUtil.getTtl(blockService, 2000);
        TransactionDetailsParams detailsParams = TransactionDetailsParams.builder().ttl(ttl).build();
        
        // See https://cips.cardano.org/cips/cip20/
        Metadata metadata = MetadataUtil.generateMsgMetadataFromFormData((Object[]) props.get("metadata"), row);
        
        final BigInteger fee = feeCalculationService.calculateFee(paymentList, detailsParams, metadata);

        BigInteger feeLimit = MAX_FEE_LIMIT;
        if (!getPropertyString("feeLimit").isBlank()) {
            feeLimit = ADAConversionUtil.adaToLovelace(new BigDecimal(getPropertyString("feeLimit")));
        }
        if (!TransactionUtil.checkFeeLimit(fee, feeLimit)) {
            LogUtil.warn(getClassName(), "Send transaction aborted. Transaction fee in units of lovelace of " + fee.toString() + " exceeded set fee limit of " + feeLimit.toString() + ".");
            storeToWorkflowVariable(wfAssignment.getActivityId(), isTest, null, null);
            return null;
        }
        paymentList.get(0).setFee(fee);

        Result<TransactionResult> transactionResult = transactionHelperService.transfer(paymentList, detailsParams, metadata);

        if (!transactionResult.isSuccessful()) {
            LogUtil.warn(getClassName(), "Transaction failed with status code " + transactionResult.code() + ". Response returned --> " + transactionResult.getResponse());
            storeToWorkflowVariable(wfAssignment.getActivityId(), isTest, null, null);
            return null;
        }

        //Store successful unvalidated txn result first
        storeToWorkflowVariable(wfAssignment.getActivityId(), isTest, transactionResult, null);

        //Use separate thread to wait for transaction validation
        Thread waitTransactionThread = new PluginThread(() -> {
            Result<TransactionContent> validatedTransactionResult = null;

            try {
                validatedTransactionResult = TransactionUtil.waitForTransaction(transactionService, transactionResult);
            } catch (Exception ex) {
                LogUtil.error(getClassName(), ex, "Error waiting for transaction validation...");
            }

            if (validatedTransactionResult != null) {
                //Store validated/confirmed txn result for current activity instance
                storeToWorkflowVariable(wfAssignment.getActivityId(), isTest, transactionResult, validatedTransactionResult);

                //Store validated/confirmed txn result for future running activity instance
                String mostRecentActivityId = workflowManager.getRunningActivityIdByRecordId(primaryKey, wfAssignment.getProcessDefId(), null, null);
                storeToWorkflowVariable(mostRecentActivityId, isTest, transactionResult, validatedTransactionResult);
            }
        });
        waitTransactionThread.start();

        return transactionResult;
    }
    
    protected DataList getDataList() throws BeansException {
        DataList datalist = null;
        
        ApplicationContext ac = AppUtil.getApplicationContext();
        DatalistDefinitionDao datalistDefinitionDao = (DatalistDefinitionDao) ac.getBean("datalistDefinitionDao");
        final String datalistId = getPropertyString("datalistId");
        DatalistDefinition datalistDefinition = datalistDefinitionDao.loadById(datalistId, appDef);

        if (datalistDefinition != null) {
            datalist = dataListService.fromJson(datalistDefinition.getJson());
        }
        
        return datalist;
    }
    
    //Get receiver(s) & their respective amounts to send from binder from user-selected datalist
    protected List<PaymentTransaction> getPaymentListFromBinderData(Account senderAccount) {
        List<PaymentTransaction> paymentList = new ArrayList<>();
            
        try {
            DataList datalist = getDataList();
            DataListCollection binderData = datalist.getRows();
            
            if (binderData == null || binderData.isEmpty()) {
                return null;
            }
            
            // Unit var placed outside of for-loop to avoid redundant calls just to get payment unit
            String unit = getPaymentUnit();
            
            for (Object r : binderData) {
                String receiverAddress = (String) DataListService.evaluateColumnValueFromRow(r, getPropertyString("receiverAddressColumn"));
                String amount = (String) DataListService.evaluateColumnValueFromRow(r, getPropertyString("amountColumn"));

                // Skip row where receiver address or amount is empty
                if (receiverAddress == null || receiverAddress.isEmpty() || amount == null || amount.isEmpty()) {
                    continue;
                }
                
                BigInteger amountBgInt = getPaymentAmount(amount);

                // Check for illogical transfer amount of less or equal to 0
                if (amountBgInt.compareTo(BigInteger.ZERO) <= 0) {
                    LogUtil.info(getClassName(), "Skipping receiver address \"" + receiverAddress + "\" with invalid amount of \"" + amount + "\"");
                    continue;
                }
                
                PaymentTransaction paymentTransaction =
                    PaymentTransaction.builder()
                            .sender(senderAccount)
                            .receiver(receiverAddress)
                            .amount(amountBgInt)
                            .unit(unit)
                            .build();

                paymentList.add(paymentTransaction);
            }
        } catch (Exception ex) {
            LogUtil.error(getClassName(), ex, "Unable to retrieve transaction receivers data from datalist binder.");
        }
        
        return paymentList;
    }
    
    protected BigInteger getPaymentAmount(String amount) {
        String paymentUnit = getPropertyString("paymentUnit");
        
        switch (paymentUnit) {
            case LOVELACE:
                return ADAConversionUtil.adaToLovelace(new BigDecimal(amount));
            case "nativeTokens":
                return new BigDecimal(amount).toBigInteger();
            case "nft":
                return BigInteger.ONE;
        }
        
        return null;
    }
    
    protected String getPaymentUnit() {
        String paymentUnit = getPropertyString("paymentUnit");
        String policyId = getPropertyString("policyId");
        String assetName = getPropertyString("assetName");
        
        return paymentUnit.equalsIgnoreCase(LOVELACE) ? LOVELACE : TokenUtil.getAssetId(policyId, assetName);
    }
    
    protected void storeToWorkflowVariable(
            String activityId,
            boolean isTest, 
            Result<TransactionResult> transactionResult, 
            Result<TransactionContent> validatedtransactionResult) {
        
        String transactionSuccessfulVar = getPropertyString("wfTransactionSuccessful");
        String transactionValidatedVar = getPropertyString("wfTransactionValidated");
        String transactionIdVar = getPropertyString("wfTransactionId");
        String transactionUrlVar = getPropertyString("wfTransactionExplorerUrl");
        
        storeValuetoActivityVar(
                activityId, 
                transactionSuccessfulVar, 
                transactionResult != null ? String.valueOf(transactionResult.isSuccessful()) : "false"
        );
        storeValuetoActivityVar(
                activityId, 
                transactionValidatedVar, 
                validatedtransactionResult != null ? String.valueOf(validatedtransactionResult.isSuccessful()) : "false"
        );
        storeValuetoActivityVar(
                activityId, 
                transactionIdVar, 
                transactionResult != null ? transactionResult.getValue().getTransactionId() : ""
        );
        storeValuetoActivityVar(
                activityId, 
                transactionUrlVar, 
                transactionResult != null ? ExplorerLinkUtil.getTransactionExplorerUrl(isTest, transactionResult.getValue().getTransactionId()) : ""
        );
    }
    
    private void storeValuetoActivityVar(String activityId, String variable, String value) {
        if (activityId == null || activityId.isEmpty() || variable.isEmpty() || value == null) {
            return;
        }
        
        workflowManager.activityVariable(activityId, variable, value);
    }
    
    @Override
    public void webService(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        boolean isAdmin = WorkflowUtil.isCurrentUserInRole(WorkflowUserManager.ROLE_ADMIN);
        if (!isAdmin) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        
        String action = request.getParameter("action");
        if ("getDatalistColumns".equals(action)) {
            try {
                ApplicationContext ac = AppUtil.getApplicationContext();
                AppDefinition appDef = AppUtil.getCurrentAppDefinition();
                DatalistDefinitionDao datalistDefinitionDao = (DatalistDefinitionDao) ac.getBean("datalistDefinitionDao");
                DataListService dataListService = (DataListService) ac.getBean("dataListService");
                
                String datalistId = request.getParameter("id");
                DatalistDefinition datalistDefinition = datalistDefinitionDao.loadById(datalistId, appDef);
                
                DataList datalist;
                if (datalistDefinition != null) {
                    datalist = dataListService.fromJson(datalistDefinition.getJson());
                    DataListColumn[] datalistcolumns = datalist.getColumns();
                    
                    //JSONObject jsonObject = new JSONObject();
                    JSONArray columns = new JSONArray();
                    for (DataListColumn datalistcolumn : datalistcolumns) {
                        JSONObject column = new JSONObject();
                        column.put("value", datalistcolumn.getName());
                        column.put("label", datalistcolumn.getLabel());
                        columns.put(column);
                    }
                    columns.write(response.getWriter());
                } else {
                    JSONArray columns = new JSONArray();
                    columns.write(response.getWriter());
                }
            } catch (Exception e) {
                LogUtil.error(getClassName(), e, "Unable to retrieve datalist columns for plugin properties.");
            } 
        } else {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }
}
