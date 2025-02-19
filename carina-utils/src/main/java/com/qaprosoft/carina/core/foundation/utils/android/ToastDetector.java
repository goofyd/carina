/*******************************************************************************
 * Copyright 2020-2022 Zebrunner Inc (https://www.zebrunner.com).
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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.qaprosoft.carina.core.foundation.utils.android;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.FluentWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ToastDetector implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final String TOAST_PATTERN = "//*[@text='%s']";

    private boolean isPresent = false;

    private int waitTimeout = 20;

    private WebDriver webDriver;

    private String toastToWait;

    public ToastDetector(WebDriver webDriver, String toastToWait) {
        this.webDriver = webDriver;
        this.toastToWait = toastToWait;
    }

    public void setToastToWait(String toastToWait) {
        this.toastToWait = toastToWait;
    }

    public void setWaitTimeout(int waitTimeout) {
        if (waitTimeout > 60) {
            LOGGER.warn("Max wait timeout 60 second!");
            this.waitTimeout = 60;
            return;
        }
        this.waitTimeout = waitTimeout;
    }

    public boolean isPresent() {
        return isPresent;
    }

    @Override
    public void run() {
        waitForToast();

    }

    private void waitForToast() {
        LOGGER.info("Wait for toast...");
        isPresent = false;
        FluentWait<WebDriver> fluentWait = new FluentWait<>(webDriver);
        fluentWait.withTimeout(Duration.ofSeconds(waitTimeout)).pollingEvery(Duration.ofMillis(300)).until(input -> {
            List<?> webElemenList = webDriver.findElements(By.xpath(String.format(TOAST_PATTERN, toastToWait)));
            if (webElemenList.size() == 1) {
                LOGGER.info("Toast with text present: " + toastToWait);
                isPresent = true;
                return true;
            } else {
                return false;
            }
        });
    }

    public void startFinding() {
        Thread thread = new Thread(this);
        try {
            thread.start();
        } catch (Exception ignored) {

        }
    }
}
