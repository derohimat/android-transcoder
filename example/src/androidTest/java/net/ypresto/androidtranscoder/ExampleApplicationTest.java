/*
package net.ypresto.androidtranscoder;

import android.app.Application;
import android.test.ApplicationTestCase;


public class ApplicationTest extends ApplicationTestCase<Application> {
    public ApplicationTest() {
        super(Application.class);
    }
}
*/
package net.ypresto.androidtranscoder;

import net.ypresto.androidtranscoder.tests.SingleFileTranscoderTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

// Runs all unit tests.

@RunWith(Suite.class)
@Suite.SuiteClasses({SingleFileTranscoderTest.class})
public class ExampleApplicationTest {}
