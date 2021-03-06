/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.account;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.commons.HelixNotifier;
import com.github.ambry.commons.HelixStoreOperator;
import com.github.ambry.config.HelixPropertyStoreConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.utils.TestUtils;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static com.github.ambry.account.AccountTestUtils.*;
import static com.github.ambry.utils.TestUtils.*;
import static com.github.ambry.utils.Utils.*;
import static org.junit.Assert.*;


/**
 * Integration tests for {@link AccountUpdateTool}. The tests are running against a locally-deployed {@code ZooKeeper}.
 *
 */
@RunWith(Parameterized.class)
public class AccountUpdateToolTest {
  private static final int NUM_REF_ACCOUNT = 10 + (int) (Math.random() * 50);
  private static final int NUM_CONTAINER_PER_ACCOUNT = 4;
  private static final int ZK_SERVER_PORT = 2200;
  private static final String DC_NAME = "testDc";
  private static final byte DC_ID = (byte) 1;
  private static final String ZK_SERVER_ADDRESS = "localhost:" + ZK_SERVER_PORT;
  private static final String HELIX_STORE_ROOT_PATH = "/ambry/defaultCluster/helixPropertyStore";
  private static final int LATCH_TIMEOUT_MS = 1000;
  private static final Properties helixConfigProps = new Properties();
  private static final VerifiableProperties vHelixConfigProps;
  private static final HelixPropertyStoreConfig storeConfig;
  private static ZkInfo zkInfo;
  private static String tempDirPath;
  private final Map<Short, Account> idToRefAccountMap;
  private final Map<Short, Map<Short, Container>> idToRefContainerMap;
  private final HelixNotifier notifier;
  private final AccountService accountService;
  private final TestAccountUpdateConsumer accountUpdateConsumer;
  private final HelixStoreOperator storeOperator;
  private final short containerJsonVersion;

  /**
   * Initialization for all the tests.
   */
  static {
    helixConfigProps.setProperty(HelixPropertyStoreConfig.HELIX_PROPERTY_STORE_PREFIX + "zk.client.connect.string",
        ZK_SERVER_ADDRESS);
    helixConfigProps.setProperty(HelixPropertyStoreConfig.HELIX_PROPERTY_STORE_PREFIX + "root.path",
        HELIX_STORE_ROOT_PATH);
    vHelixConfigProps = new VerifiableProperties(helixConfigProps);
    storeConfig = new HelixPropertyStoreConfig(vHelixConfigProps);
    try {
      zkInfo = new ZkInfo(tempDirPath, DC_NAME, DC_ID, ZK_SERVER_PORT, true);
    } catch (IOException e) {
      fail("Failed to instantiate a ZooKeeper server.");
    }
  }

  /**
   * Run this test for all versions of the container schema.
   * @return the constructor arguments to use.
   */
  @Parameterized.Parameters
  public static List<Object[]> data() {
    return Arrays.asList(new Object[][]{{Container.JSON_VERSION_1}, {Container.JSON_VERSION_2}});
  }

  /**
   * Constructor that does initialization for every test.
   * @throws Exception Any unexpected exception.
   */
  public AccountUpdateToolTest(short containerJsonVersion) throws Exception {
    this.containerJsonVersion = containerJsonVersion;
    idToRefAccountMap = new HashMap<>();
    idToRefContainerMap = new HashMap<>();
    generateRefAccounts(idToRefAccountMap, idToRefContainerMap, new HashSet<>(), NUM_REF_ACCOUNT,
        NUM_CONTAINER_PER_ACCOUNT);
    tempDirPath = getTempDir("accountUpdateTool-");

    // clean up data on ZooKeeper
    storeOperator = new HelixStoreOperator(storeConfig);
    if (storeOperator.exist("/")) {
      storeOperator.delete("/");
    }

    // instantiate a HelixAccountService and listen to the change for account metadata
    notifier = new HelixNotifier(storeConfig);
    accountService =
        new HelixAccountServiceFactory(vHelixConfigProps, new MetricRegistry(), notifier).getAccountService();
    accountUpdateConsumer = new TestAccountUpdateConsumer();
    accountService.addAccountUpdateConsumer(accountUpdateConsumer);
  }

  @After
  public void cleanUp() throws Exception {
    if (accountService != null) {
      accountService.removeAccountUpdateConsumer(accountUpdateConsumer);
      accountService.close();
    }
  }

  @AfterClass
  public static void destroy() throws Exception {
    zkInfo.shutdown();
  }

  /**
   * Tests creating {@link Account}s to {@code ZooKeeper}, where no {@link Account} exists.
   * @throws Exception Any unexpected exception.
   */
  @Test
  public void testCreateAccount() throws Exception {
    assertEquals("Wrong number of accounts", 0, accountService.getAllAccounts().size());
    createOrUpdateAccountsAndWait(idToRefAccountMap.values());
    assertAccountsInAccountService(idToRefAccountMap.values(), NUM_REF_ACCOUNT, accountService);
  }

  /**
   * Tests updating {@link Account}s to {@code ZooKeeper}, where the {@link Account}s to update already exist.
   * @throws Exception Any unexpected exception.
   */
  @Test
  public void testUpdateAccount() throws Exception {
    // first, create NUM_REF_ACCOUNT accounts through the tool
    createOrUpdateAccountsAndWait(idToRefAccountMap.values());
    assertAccountsInAccountService(idToRefAccountMap.values(), NUM_REF_ACCOUNT, accountService);

    // then, update the name of all the accounts again through the tool
    String accountNameAppendix = "-accountNameAppendix";
    String containerNameAppendix = "-containerNameAppendix";
    Collection<Account> updatedAccounts = new ArrayList<>();
    for (Account account : accountService.getAllAccounts()) {
      AccountBuilder accountBuilder = new AccountBuilder(account).setName(account.getName() + accountNameAppendix);
      for (Container container : account.getAllContainers()) {
        accountBuilder.addOrUpdateContainer(
            new ContainerBuilder(container).setName(container.getName() + containerNameAppendix).build());
      }
      updatedAccounts.add(accountBuilder.build());
    }
    createOrUpdateAccountsAndWait(updatedAccounts);
    assertAccountsInAccountService(updatedAccounts, NUM_REF_ACCOUNT, accountService);
  }

  /**
   * Tests updating {@link Account}s that are conflicting.
   * @throws Exception Any unexpected exception.
   */
  @Test
  public void testUpdateConflictAccounts() throws Exception {
    Collection<Account> idConflictAccounts = new ArrayList<>();
    // id conflict
    idConflictAccounts.add(new AccountBuilder((short) 1, "account1", Account.AccountStatus.INACTIVE, null).build());
    idConflictAccounts.add(new AccountBuilder((short) 1, "account2", Account.AccountStatus.INACTIVE, null).build());
    TestUtils.assertException(IllegalArgumentException.class, () -> createOrUpdateAccountsAndWait(idConflictAccounts),
        null);
    TestUtils.assertException(IllegalArgumentException.class, () -> createOrUpdateAccountsAndWait(idConflictAccounts),
        null);
    Thread.sleep(100);
    assertEquals("Wrong number of accounts in accountService", 0, accountService.getAllAccounts().size());

    // name conflict
    Collection<Account> nameConflictAccounts = new ArrayList<>();
    nameConflictAccounts.add(new AccountBuilder((short) 1, "account1", Account.AccountStatus.INACTIVE, null).build());
    nameConflictAccounts.add(new AccountBuilder((short) 2, "account1", Account.AccountStatus.INACTIVE, null).build());
    TestUtils.assertException(IllegalArgumentException.class, () -> createOrUpdateAccountsAndWait(nameConflictAccounts),
        null);
    TestUtils.assertException(IllegalArgumentException.class, () -> createOrUpdateAccountsAndWait(nameConflictAccounts),
        null);
    Thread.sleep(100);
    assertEquals("Wrong number of accounts in accountService", 0, accountService.getAllAccounts().size());
  }

  /**
   * Tests creating/updating {@link Account}s using bad file containing no valid json array.
   * @throws Exception Any unexpected exception.
   */
  @Test
  public void testBadJsonFile() throws Exception {
    String badJsonFile = tempDirPath + File.separator + "badJsonFile.json";
    writeStringToFile("Invalid json string", badJsonFile);
    try {
      AccountUpdateTool.updateAccount(badJsonFile, ZK_SERVER_ADDRESS, HELIX_STORE_ROOT_PATH, 2000, 2000,
          Container.getCurrentJsonVersion());
      fail("Should have thrown.");
    } catch (Exception e) {
      // expected
    }
    Thread.sleep(100);
    assertEquals("Wrong number of accounts in accountService", 0, accountService.getAllAccounts().size());
  }

  /**
   * Creates or updates a collection of {@link Account}s to {@code ZooKeeper} server.
   * @param accounts The collection of {@link Account}s to create or update.
   * @throws Exception Any unexpected exception.
   */
  private void createOrUpdateAccountsAndWait(Collection<Account> accounts) throws Exception {
    String jsonFilePath = tempDirPath + File.separator + UUID.randomUUID().toString() + ".json";
    writeAccountsToFile(accounts, jsonFilePath);
    accountUpdateConsumer.reset();
    AccountUpdateTool.updateAccount(jsonFilePath, ZK_SERVER_ADDRESS, HELIX_STORE_ROOT_PATH, 2000, 2000,
        containerJsonVersion);
    accountUpdateConsumer.awaitUpdate();
  }

  /**
   * Writes a collection of {@link Account}s into a file. The file contains and only contains a {@link JSONArray}, where
   * each element is the json string of the {@link Account}.
   * @param accounts The collection of {@link Account}s to write to the file.
   * @param filePath The path of the file to write.
   * @throws Exception Any unexpected exception.
   */
  private static void writeAccountsToFile(Collection<Account> accounts, String filePath) throws Exception {
    JSONArray accountArray = new JSONArray();
    for (Account account : accounts) {
      accountArray.put(account.toJson());
    }
    writeJsonArrayToFile(accountArray, filePath);
  }

  /**
   * An account update consumer for testing purposes. This includes facilities to wait for an update to occur.
   */
  private static class TestAccountUpdateConsumer implements Consumer<Collection<Account>> {
    private CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void accept(Collection<Account> accounts) {
      latch.countDown();
    }

    /**
     * Wait for at least one account update since the last {@link #reset()}.
     * @throws TimeoutException
     * @throws InterruptedException
     */
    private void awaitUpdate() throws TimeoutException, InterruptedException {
      TestUtils.awaitLatchOrTimeout(latch, LATCH_TIMEOUT_MS);
    }

    /**
     * Reset the latch.
     */
    private void reset() {
      latch = new CountDownLatch(1);
    }
  }
}
