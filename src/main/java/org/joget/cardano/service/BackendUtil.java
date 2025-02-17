package org.joget.cardano.service;

import com.bloxbean.cardano.client.backend.api.BackendService;
import static com.bloxbean.cardano.client.backend.blockfrost.common.Constants.BLOCKFROST_MAINNET_URL;
import static com.bloxbean.cardano.client.backend.blockfrost.common.Constants.BLOCKFROST_PREVIEW_URL;
import static com.bloxbean.cardano.client.backend.blockfrost.common.Constants.BLOCKFROST_PREPOD_URL;
import static com.bloxbean.cardano.client.backend.koios.Constants.KOIOS_TESTNET_URL;
import static com.bloxbean.cardano.client.backend.koios.Constants.KOIOS_MAINNET_URL;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.koios.KoiosBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import java.util.Map;

public class BackendUtil {
    
    //TO-DO: Support using preprod testnet
    
    //Still waiting for koios to update their APIs
    public static final String KOIOS_PREVIEW_TESTNET_URL = "";
    public static final String KOIOS_PREPROD_TESTNET_URL = "";
    
    public static boolean isTestnet(Map properties) {
        String networkType = (String) properties.get("networkType");
        
        return "testnet".equalsIgnoreCase(networkType) || //Don't delete this, to prevent loss of existing configs from previous versions
                "previewTestnet".equalsIgnoreCase(networkType) || 
                "preprodTestnet".equalsIgnoreCase(networkType);
    }
    
    public static BackendService getBackendService(Map properties) {
        boolean isTest = isTestnet(properties);
        
        String backendServiceName = (String) properties.get("backendService");
        String blockfrostProjectKey = (String) properties.get("blockfrostProjectKey");
        
        BackendService backend;
        
        switch (backendServiceName) {
            case "blockfrost":
                backend = getBlockfrostBackendService(isTest ? BLOCKFROST_PREVIEW_URL : BLOCKFROST_MAINNET_URL, blockfrostProjectKey);
                break;
            case "koios":
            default:
                backend = getKoiosBackendService(isTest ? KOIOS_TESTNET_URL : KOIOS_MAINNET_URL);
                break;
        }
        
        return backend;
    }
    
    private static BackendService getBlockfrostBackendService(String blockfrostEndpointUrl, String blockfrostProjectKey) {
        return new BFBackendService(blockfrostEndpointUrl, blockfrostProjectKey);
    }
    
    private static BackendService getKoiosBackendService(String koiosEndpointUrl) {
        return new KoiosBackendService(koiosEndpointUrl);
    }
    
    public static Network getNetwork(boolean isTest) {
        return isTest ? Networks.preview() : Networks.mainnet();
    }
}
