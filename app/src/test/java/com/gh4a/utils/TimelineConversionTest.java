package com.gh4a.utils;

import static org.junit.Assert.assertNotNull;

import com.meisolsson.githubsdk.core.ServiceGenerator;
import com.meisolsson.githubsdk.model.IssueEvent;
import com.squareup.moshi.Types;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TimelineConversionTest {
    @Test
    public void codebergTimelinesParseWithoutNullEvents() throws Exception {
        Method convert = ForgejoCompatInterceptor.class
                .getDeclaredMethod("convertIssueTimeline", String.class);
        convert.setAccessible(true);
        File dir = new File("src/test/resources/timeline");
        StringBuilder problems = new StringBuilder();
        for (File f : dir.listFiles()) {
            String json = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            if (!json.trim().startsWith("[")) {
                continue;
            }
            String converted = ForgejoCompatInterceptor.aliasStarFields(
                    (String) convert.invoke(null, json));
            List<IssueEvent> events = ServiceGenerator.moshi
                    .<List<IssueEvent>>adapter(Types.newParameterizedType(List.class, IssueEvent.class))
                    .fromJson(converted);
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i) == null || events.get(i).event() == null) {
                    problems.append(f.getName()).append('#').append(i).append(' ');
                }
            }
        }
        assertNotNull(problems.toString());
        if (problems.length() > 0) {
            throw new AssertionError("null events: " + problems);
        }
    }
}
