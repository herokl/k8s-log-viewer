package com.longfor.rsa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;

@SuppressWarnings("unused")
public class TestRSAUtil {
    private static final Logger logger = LoggerFactory.getLogger(TestRSAUtil.class);

    public static final String APOLLO_PRIVATE_KEY = "apollo.private.key";
    public static final String DEFAULT_KEY_IN_CLASSPATH = "apollo_private_key";

    static String apolloPrivateKeyStr = "";
    private static PrivateKey apolloPrivateKey = null;


    static String value = "$#G2GtxaLN0kiE9jZVgs6hpSj9Zy5aMIUt4dk+BeBIM95DOwTmGm96MRaFYQVns1n2h6EipfyLKL1LXucLAqVxwwIz7F1sZ1SZGeL6Lm9q4enoJ4KHwbYkW51xTBKzasDrZk2TNIOh6q1JOc/Z7kT4o77599uXakRsjeWYIADv6hLi8uuNGGaadr9CI3VZ6fBs0sJTMlwMefo1FR/fKbJ40nzMU/fFjej7d0Hjn6bMxK+ARC79Naa36P/RWDobH7fTU+lu+kqM7c1Sf2I+00VB5vua76n+l6EWsQk5qKcwRegNSGtR4yAvNZfcOVPzIy29dInWfDmJ+/coA6yUvt5ADw==";

    private static void initApolloPrivateKey() {
        String apolloPrivateKeyStr = "";
        try {
            File file = new File("D:\\workandstudy\\spacework\\java\\k8s-log-viewer\\src\\test\\resources\\apollo_private_key");
            Path path = file.toPath();
            apolloPrivateKeyStr = Files.readString(path);
            if (!apolloPrivateKeyStr.isEmpty()) {
                apolloPrivateKey = RSAUtil.loadPrivateKey(apolloPrivateKeyStr);
            }
        } catch (IOException e) {
            logger.error("read private key failed!", e);
        } catch (Exception e) {
            logger.error("load private key failed!", e);
        }
    }

    public static void main(String[] args) throws Exception {
        initApolloPrivateKey();
        System.out.println(RSAUtil.decrypt(apolloPrivateKey, value));
    }
}
