package org.cache2k.test.core.expiry;

/*
 * #%L
 * cache2k implementation
 * %%
 * Copyright (C) 2000 - 2020 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.cache2k.test.util.TestingBase;
import org.cache2k.Cache;
import org.cache2k.CacheEntry;
import org.cache2k.core.HeapCache;
import org.cache2k.core.util.TunableFactory;
import org.cache2k.expiry.ExpiryPolicy;
import org.cache2k.processor.EntryProcessor;
import org.cache2k.processor.MutableCacheEntry;
import org.cache2k.test.core.TestingParameters;
import org.cache2k.test.util.Condition;
import org.cache2k.testing.category.SlowTests;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Test variants of cache mutators and check for correct expiry.
 *
 * @author Jens Wilke
 */
@Category(SlowTests.class) @RunWith(Parameterized.class)
public class AllMutatorsExpireTest extends TestingBase {

  final static long EXPIRY_BEYOND_GAP = TunableFactory.get(HeapCache.Tunable.class).sharpExpirySafetyGapMillis + 3;
  final static Integer KEY = 1;
  final static Integer VALUE = 1;

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    List<Pars> parameterList = new ArrayList<Pars>();
    for (int variant = 0; variant <= 7; variant++) {
      for (long _expiry : new long[]{0, TestingParameters.MINIMAL_TICK_MILLIS}) {
        for (boolean _sharpExpiry : new boolean[]{false, true}) {
          for (boolean _keepData :  new boolean[]{false, true}) {
            Pars p = new Pars();
            p.variant = variant;
            p.expiryDurationMillis = _expiry;
            p.sharpExpiry = _sharpExpiry;
            p.keepData = _keepData;
            parameterList.add(p);
          }
        }
      }
    }
    for (int variant = 0; variant <= 7; variant++) {
      for (long _expiry : new long[]{EXPIRY_BEYOND_GAP}) {
        for (boolean _sharpExpiry : new boolean[]{true}) {
          for (boolean _keepData :  new boolean[]{false}) {
            Pars p = new Pars();
            p.variant = variant;
            p.expiryDurationMillis = _expiry;
            p.sharpExpiry = _sharpExpiry;
            p.keepData = _keepData;
            parameterList.add(p);
          }
        }
      }
    }
    return buildParameterCollection(parameterList);
  }

  static Collection<Object[]> buildParameterCollection(List<Pars> parameters) {
    List<Object[]> l = new ArrayList<Object[]>();
    for (Pars o : parameters) {
      l.add(new Object[]{o});
    }
    return l;
  }

  Pars pars;

  public AllMutatorsExpireTest(Pars p) { pars = p; }


  @Test
  public void test() throws Exception {
    if (pars.sharpExpiry) {
      putExpiresSharply(pars.expiryDurationMillis, pars.variant, pars.keepData);
    } else {
      putExpiresLagging(pars.expiryDurationMillis, pars.variant, pars.keepData);
    }
  }

  void putExpiresSharply(final long expiryTime, final int variant, boolean keepData) {
    final Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .name(getAndSetCacheName("putExpiresSharply"))
      .expiryPolicy(new ExpiryPolicy<Integer, Integer>() {
        @Override
        public long calculateExpiryTime(final Integer key, final Integer value, final long loadTime, final CacheEntry<Integer, Integer> oldEntry) {
          return loadTime + expiryTime;
        }
      })
      .sharpExpiry(true)
      .keepDataAfterExpired(keepData)
      .build();
    final AtomicInteger _opCnt = new AtomicInteger();
    within(expiryTime)
      .work(new Runnable() {
        @Override
        public void run() {
          _opCnt.set(mutate(variant, c));
        }
      }).check(new Runnable() {
      @Override
      public void run() {
        assertTrue(c.containsKey(1));
      }
    });
    sleep(expiryTime);
    long _laggingMillis = 0;
    if (c.containsKey(KEY)) {
      long t1 = millis();
      await(new Condition() {
        @Override
        public boolean check() {
          return !c.containsKey(KEY);
        }
      });
      _laggingMillis = millis() - t1 + 1;
    }
    assertFalse(c.containsKey(KEY));
    assertNull(c.peek(KEY));
    assertNull(c.peekEntry(KEY));
    if (_opCnt.get() == 1) {
      await(new Condition() {
        @Override
        public boolean check() {
          return getInfo().getExpiredCount() == 1;
        }
      });
    }
    if (!pars.keepData && _opCnt.get() == 1) {
      await(new Condition() {
        @Override
        public boolean check() {
          return getInfo().getSize() == 0;
        }
      });
    }
    assertTrue("(flaky?) No lag, got delay: " + _laggingMillis, _laggingMillis == 0);
  }

  private String getAndSetCacheName(final String methodName) {
    return cacheName = this.getClass().getSimpleName() + "-" + methodName + "-" +
      pars.toString().replace('=', '-');
  }

  void putExpiresLagging(long expiryTime, final int variant, boolean keepData) {
    final Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .name(getAndSetCacheName("putExpiresLagging"))
      .expireAfterWrite(expiryTime, TimeUnit.MILLISECONDS)
      .sharpExpiry(false)
      .keepDataAfterExpired(keepData)
      .build();
    final AtomicInteger _opCnt = new AtomicInteger();
    within(expiryTime)
      .work(new Runnable() {
        @Override
        public void run() {
          _opCnt.set(mutate(variant, c));
        }
      }).check(new Runnable() {
      @Override
      public void run() {
        assertTrue(c.containsKey(1));
      }
    });
    await(new Condition() {
      @Override
      public boolean check() {
        return !c.containsKey(KEY);
      }
    });
    assertNull(c.peek(KEY));
    assertNull(c.peekEntry(KEY));
    if (_opCnt.get() == 1) {
      await(new Condition() {
        @Override
        public boolean check() {
          return getInfo().getExpiredCount() == 1;
        }
      });
    }
  }

  int mutate(final int variant, final Cache<Integer, Integer> c) {
    int opCnt = 1;
    switch (variant) {
      case 0:
        c.put(KEY, VALUE);
        break;
      case 1:
        c.putIfAbsent(KEY, VALUE);
        break;
      case 2:
        c.peekAndPut(KEY, VALUE);
        break;
      case 3:
        c.put(1,1);
        c.peekAndReplace(KEY, VALUE);
        opCnt++;
        break;
      case 4:
        c.put(KEY, VALUE);
        c.replace(KEY, VALUE);
        opCnt++;
        break;
      case 5:
        c.put(KEY, VALUE);
        c.replaceIfEquals(KEY, VALUE, VALUE);
        opCnt++;
        break;
      case 6:
        c.invoke(KEY, new EntryProcessor<Integer, Integer, Object>() {
          @Override
          public Object process(final MutableCacheEntry<Integer, Integer> e) throws Exception {
            e.setValue(VALUE);
            return null;
          }
        });
        break;
      case 7:
        c.put(KEY, VALUE);
        c.put(KEY, VALUE);
        opCnt++;
        break;
    }
    return opCnt;
  }

  static class Pars {
    int variant;
    boolean sharpExpiry;
    long expiryDurationMillis;
    boolean keepData;

    @Override
    public String toString() {
      return
        "expiryDurationMillis=" + expiryDurationMillis +
        ",variant=" + variant +
        ",sharpExpiry=" + sharpExpiry +
        ",keepData=" + keepData;
    }
  }

}
