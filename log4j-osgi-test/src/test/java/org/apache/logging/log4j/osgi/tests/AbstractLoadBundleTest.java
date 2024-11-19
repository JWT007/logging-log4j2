/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.logging.log4j.osgi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.util.ServiceLoaderUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Tests a basic Log4J 'setup' in an OSGi container.
 */
abstract class AbstractLoadBundleTest {

    private BundleContext bundleContext;

    @RegisterExtension
    public final OsgiExt osgi;

    AbstractLoadBundleTest(final FrameworkFactory frameworkFactory) {
        this.osgi = new OsgiExt(frameworkFactory);
    }

    @BeforeEach
    public void before() {
        bundleContext = osgi.getFramework().getBundleContext();
    }

    private Bundle installBundle(final String symbolicName) throws BundleException {
        // The links are generated by 'exam-maven-plugin'
        final String url = String.format("link:classpath:%s.link", symbolicName);
        return bundleContext.installBundle(url);
    }

    private Bundle getApiBundle() throws BundleException {
        return installBundle("org.apache.logging.log4j.api");
    }

    private Bundle getCoreBundle() throws BundleException {
        return installBundle("org.apache.logging.log4j.core");
    }

    private Bundle get12ApiBundle() throws BundleException {
        return installBundle("org.apache.logging.log4j.1.2.api");
    }

    private Bundle getApiTestsBundle() throws BundleException {
        return installBundle("org.apache.logging.log4j.api.test");
    }

    /**
     * Tests starting, then stopping, then restarting, then stopping, and finally uninstalling the API and Core bundles
     */
    @Test
    public void testApiCoreStartStopStartStop() throws BundleException {

        final Bundle api = getApiBundle();
        final Bundle core = getCoreBundle();
        assertEquals(Bundle.INSTALLED, api.getState(), "api is not in INSTALLED state");
        assertEquals(Bundle.INSTALLED, core.getState(), "core is not in INSTALLED state");

        // 1st start-stop
        doOnBundlesAndVerifyState(Bundle::start, Bundle.ACTIVE, api, core);
        doOnBundlesAndVerifyState(Bundle::stop, Bundle.RESOLVED, core, api);

        // 2nd start-stop
        doOnBundlesAndVerifyState(Bundle::start, Bundle.ACTIVE, api, core);
        doOnBundlesAndVerifyState(Bundle::stop, Bundle.RESOLVED, core, api);

        doOnBundlesAndVerifyState(Bundle::uninstall, Bundle.UNINSTALLED, core, api);
    }

    /**
     * Tests LOG4J2-1637.
     */
    @Test
    public void testClassNotFoundErrorLogger() throws BundleException {

        final Bundle api = getApiBundle();
        final Bundle core = getCoreBundle();

        doOnBundlesAndVerifyState(Bundle::start, Bundle.ACTIVE, api);
        // fails if LOG4J2-1637 is not fixed
        try {
            core.start();
        } catch (final BundleException error0) {
            boolean log4jClassNotFound = false;
            final Throwable error1 = error0.getCause();
            if (error1 != null) {
                final Throwable error2 = error1.getCause();
                if (error2 != null) {
                    log4jClassNotFound = error2.toString()
                            .startsWith("java.lang.ClassNotFoundException: org.apache.logging.log4j.Logger");
                }
            }
            if (!log4jClassNotFound) {
                throw error0;
            }
        }
        assertEquals(Bundle.ACTIVE, core.getState(), String.format("`%s` bundle state mismatch", core));

        doOnBundlesAndVerifyState(Bundle::stop, Bundle.RESOLVED, core, api);
        doOnBundlesAndVerifyState(Bundle::uninstall, Bundle.UNINSTALLED, core, api);
    }

    /**
     * Tests the loading of the 1.2 Compatibility API bundle, its classes should be loadable from the Core bundle,
     * and the class loader should be the same between a class from core and a class from compat
     */
    @Test
    public void testLog4J12Fragement() throws BundleException, ReflectiveOperationException {

        final Bundle api = getApiBundle();
        final Bundle core = getCoreBundle();
        final Bundle compat = get12ApiBundle();

        doOnBundlesAndVerifyState(Bundle::start, Bundle.ACTIVE, api, core);

        final Class<?> coreClassFromCore = core.loadClass("org.apache.logging.log4j.core.Core");
        final Class<?> levelClassFrom12API = core.loadClass("org.apache.log4j.Level");
        final Class<?> levelClassFromAPI = core.loadClass("org.apache.logging.log4j.Level");

        assertEquals(
                levelClassFrom12API.getClassLoader(),
                coreClassFromCore.getClassLoader(),
                "expected 1.2 API Level to have the same class loader as Core");
        assertNotEquals(
                levelClassFrom12API.getClassLoader(),
                levelClassFromAPI.getClassLoader(),
                "expected 1.2 API Level NOT to have the same class loader as API Level");

        doOnBundlesAndVerifyState(Bundle::stop, Bundle.RESOLVED, core, api);
        doOnBundlesAndVerifyState(Bundle::uninstall, Bundle.UNINSTALLED, compat, core, api);
    }

    /**
     * Tests whether the {@link ServiceLoaderUtil} finds services in other bundles.
     */
    @Test
    public void testServiceLoader() throws BundleException, ReflectiveOperationException {
        final Bundle api = getApiBundle();
        final Bundle core = getCoreBundle();
        final Bundle apiTests = getApiTestsBundle();

        final Class<?> osgiServiceLocator = api.loadClass("org.apache.logging.log4j.util.OsgiServiceLocator");
        assertTrue((boolean) osgiServiceLocator.getMethod("isAvailable").invoke(null), "OsgiServiceLocator is active");

        doOnBundlesAndVerifyState(Bundle::start, Bundle.ACTIVE, api, core, apiTests);

        final Class<?> osgiServiceLocatorTest =
                apiTests.loadClass("org.apache.logging.log4j.test.util.OsgiServiceLocatorTest");

        final Method loadProviders = osgiServiceLocatorTest.getDeclaredMethod("loadProviders");
        final Object obj = loadProviders.invoke(null);
        assertTrue(obj instanceof Stream);
        @SuppressWarnings("unchecked")
        final List<Object> services = ((Stream<Object>) obj).collect(Collectors.toList());
        assertEquals(1, services.size());
        assertEquals(
                "org.apache.logging.log4j.core.impl.Log4jProvider",
                services.get(0).getClass().getName());

        doOnBundlesAndVerifyState(Bundle::stop, Bundle.RESOLVED, apiTests, core, api);
        doOnBundlesAndVerifyState(Bundle::uninstall, Bundle.UNINSTALLED, apiTests, core, api);
    }

    private static void doOnBundlesAndVerifyState(
            final ThrowingConsumer<Bundle> operation, final int expectedState, final Bundle... bundles) {
        for (final Bundle bundle : bundles) {
            try {
                operation.accept(bundle);
            } catch (final Throwable error) {
                final String message = String.format("operation failure for bundle `%s`", bundle);
                throw new RuntimeException(message, error);
            }
            assertEquals(expectedState, bundle.getState(), String.format("`%s` bundle state mismatch", bundle));
        }
    }
}
