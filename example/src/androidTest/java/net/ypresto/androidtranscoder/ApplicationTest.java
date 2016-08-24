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

import android.util.Log;

import net.ypresto.androidtranscoder.tests.Transcoder;

import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

// Runs all unit tests.

@RunWith(Suite.class)
@Suite.SuiteClasses({Transcoder.class})
public class ApplicationTest {}
