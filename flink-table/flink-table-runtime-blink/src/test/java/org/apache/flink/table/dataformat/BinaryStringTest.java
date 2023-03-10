/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.	See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.	You may obtain a copy of the License at
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.dataformat;

import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.core.memory.MemorySegmentFactory;
import org.apache.flink.table.runtime.operators.sort.SortUtil;
import org.apache.flink.table.runtime.util.StringUtf8Utils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.flink.table.dataformat.BinaryString.blankString;
import static org.apache.flink.table.dataformat.BinaryString.fromBytes;
import static org.apache.flink.table.dataformat.BinaryStringUtil.EMPTY_STRING_ARRAY;
import static org.apache.flink.table.dataformat.BinaryStringUtil.concat;
import static org.apache.flink.table.dataformat.BinaryStringUtil.concatWs;
import static org.apache.flink.table.dataformat.BinaryStringUtil.keyValue;
import static org.apache.flink.table.dataformat.BinaryStringUtil.reverse;
import static org.apache.flink.table.dataformat.BinaryStringUtil.splitByWholeSeparatorPreserveAllTokens;
import static org.apache.flink.table.dataformat.BinaryStringUtil.substringSQL;
import static org.apache.flink.table.dataformat.BinaryStringUtil.toByte;
import static org.apache.flink.table.dataformat.BinaryStringUtil.toDecimal;
import static org.apache.flink.table.dataformat.BinaryStringUtil.toInt;
import static org.apache.flink.table.dataformat.BinaryStringUtil.toLong;
import static org.apache.flink.table.dataformat.BinaryStringUtil.toShort;
import static org.apache.flink.table.dataformat.BinaryStringUtil.trim;
import static org.apache.flink.table.dataformat.BinaryStringUtil.trimLeft;
import static org.apache.flink.table.dataformat.BinaryStringUtil.trimRight;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Test of {@link BinaryString}.
 *
 * <p>Caution that you must construct a string by {@link #fromString} to cover all the
 * test cases.
 *
 */
@RunWith(Parameterized.class)
public class BinaryStringTest {

	private BinaryString empty = fromString("");

	private final Mode mode;

	public BinaryStringTest(Mode mode) {
		this.mode = mode;
	}

	@Parameterized.Parameters(name = "{0}")
	public static List<Mode> getVarSeg() {
		return Arrays.asList(Mode.ONE_SEG, Mode.MULTI_SEGS, Mode.STRING, Mode.RANDOM);
	}

	private enum Mode {
		ONE_SEG, MULTI_SEGS, STRING, RANDOM
	}

	private BinaryString fromString(String str) {
		BinaryString string = BinaryString.fromString(str);

		Mode mode = this.mode;

		if (mode == Mode.RANDOM) {
			int rnd = new Random().nextInt(3);
			if (rnd == 0) {
				mode = Mode.ONE_SEG;
			} else if (rnd == 1) {
				mode = Mode.MULTI_SEGS;
			} else if (rnd == 2) {
				mode = Mode.STRING;
			}
		}

		if (mode == Mode.STRING) {
			return string;
		}
		if (mode == Mode.ONE_SEG || string.getSizeInBytes() < 2) {
			string.ensureMaterialized();
			return string;
		} else {
			int numBytes = string.getSizeInBytes();
			int pad = new Random().nextInt(5);
			int numBytesWithPad = numBytes + pad;
			int segSize = numBytesWithPad / 2 + 1;
			byte[] bytes1 = new byte[segSize];
			byte[] bytes2 = new byte[segSize];
			if (segSize - pad > 0 && numBytes >= segSize - pad) {
				string.getSegments()[0].get(
						0, bytes1, pad, segSize - pad);
			}
			string.getSegments()[0].get(segSize - pad, bytes2, 0, numBytes - segSize + pad);
			return BinaryString.fromAddress(
					new MemorySegment[] {
							MemorySegmentFactory.wrap(bytes1),
							MemorySegmentFactory.wrap(bytes2)
					}, pad, numBytes);
		}
	}

	private void checkBasic(String str, int len) {
		BinaryString s1 = fromString(str);
		BinaryString s2 = fromBytes(str.getBytes(StandardCharsets.UTF_8));
		assertEquals(s1.numChars(), len);
		assertEquals(s2.numChars(), len);

		assertEquals(s1.toString(), str);
		assertEquals(s2.toString(), str);
		assertEquals(s1, s2);

		assertEquals(s1.hashCode(), s2.hashCode());

		assertEquals(0, s1.compareTo(s2));

		assertTrue(s1.contains(s2));
		assertTrue(s2.contains(s1));
		assertTrue(s1.startsWith(s1));
		assertTrue(s1.endsWith(s1));
	}

	@Test
	public void basicTest() {
		checkBasic("", 0);
		checkBasic(",", 1);
		checkBasic("hello", 5);
		checkBasic("hello world", 11);
		checkBasic("Flink????????????", 9);
		checkBasic("??? ??? ??? ???", 7);

		checkBasic("??", 1); // 2 bytes char
		checkBasic("????", 2); // 2 * 2 bytes chars
		checkBasic("?????????", 3); // 3 * 3 bytes chars
		checkBasic("\uD83E\uDD19", 1); // 4 bytes char
	}

	@Test
	public void emptyStringTest() {
		assertEquals(empty, fromString(""));
		assertEquals(empty, fromBytes(new byte[0]));
		assertEquals(0, empty.numChars());
		assertEquals(0, empty.getSizeInBytes());
	}

	@Test
	public void compareTo() {
		assertEquals(0, fromString("   ").compareTo(blankString(3)));
		assertTrue(fromString("").compareTo(fromString("a")) < 0);
		assertTrue(fromString("abc").compareTo(fromString("ABC")) > 0);
		assertTrue(fromString("abc0").compareTo(fromString("abc")) > 0);
		assertEquals(0, fromString("abcabcabc").compareTo(fromString("abcabcabc")));
		assertTrue(fromString("aBcabcabc").compareTo(fromString("Abcabcabc")) > 0);
		assertTrue(fromString("Abcabcabc").compareTo(fromString("abcabcabC")) < 0);
		assertTrue(fromString("abcabcabc").compareTo(fromString("abcabcabC")) > 0);

		assertTrue(fromString("abc").compareTo(fromString("??????")) < 0);
		assertTrue(fromString("??????").compareTo(fromString("??????")) > 0);
		assertTrue(fromString("??????123").compareTo(fromString("??????122")) > 0);

		MemorySegment segment1 = MemorySegmentFactory.allocateUnpooledSegment(1024);
		MemorySegment segment2 = MemorySegmentFactory.allocateUnpooledSegment(1024);
		SortUtil.putStringNormalizedKey(fromString("abcabcabc"), segment1, 0, 9);
		SortUtil.putStringNormalizedKey(fromString("abcabcabC"), segment2, 0, 9);
		assertTrue(segment1.compare(segment2, 0, 0, 9) > 0);
		SortUtil.putStringNormalizedKey(fromString("abcab"), segment1, 0, 9);
		assertTrue(segment1.compare(segment2, 0, 0, 9) < 0);
	}

	@Test
	public void testMultiSegments() {

		// prepare
		MemorySegment[] segments1 = new MemorySegment[2];
		segments1[0] = MemorySegmentFactory.wrap(new byte[10]);
		segments1[1] = MemorySegmentFactory.wrap(new byte[10]);
		segments1[0].put(5, "abcde".getBytes(UTF_8), 0, 5);
		segments1[1].put(0, "aaaaa".getBytes(UTF_8), 0, 5);

		MemorySegment[] segments2 = new MemorySegment[2];
		segments2[0] = MemorySegmentFactory.wrap(new byte[5]);
		segments2[1] = MemorySegmentFactory.wrap(new byte[5]);
		segments2[0].put(0, "abcde".getBytes(UTF_8), 0, 5);
		segments2[1].put(0, "b".getBytes(UTF_8), 0, 1);

		// test go ahead both
		BinaryString binaryString1 = BinaryString.fromAddress(segments1, 5, 10);
		BinaryString binaryString2 = BinaryString.fromAddress(segments2, 0, 6);
		assertEquals("abcdeaaaaa", binaryString1.toString());
		assertEquals("abcdeb", binaryString2.toString());
		assertEquals(-1, binaryString1.compareTo(binaryString2));

		// test needCompare == len
		binaryString1 = BinaryString.fromAddress(segments1, 5, 5);
		binaryString2 = BinaryString.fromAddress(segments2, 0, 5);
		assertEquals("abcde", binaryString1.toString());
		assertEquals("abcde", binaryString2.toString());
		assertEquals(0, binaryString1.compareTo(binaryString2));

		// test find the first segment of this string
		binaryString1 = BinaryString.fromAddress(segments1, 10, 5);
		binaryString2 = BinaryString.fromAddress(segments2, 0, 5);
		assertEquals("aaaaa", binaryString1.toString());
		assertEquals("abcde", binaryString2.toString());
		assertEquals(-1, binaryString1.compareTo(binaryString2));
		assertEquals(1, binaryString2.compareTo(binaryString1));

		// test go ahead single
		segments2 = new MemorySegment[]{MemorySegmentFactory.wrap(new byte[10])};
		segments2[0].put(4, "abcdeb".getBytes(UTF_8), 0, 6);
		binaryString1 = BinaryString.fromAddress(segments1, 5, 10);
		binaryString2 = BinaryString.fromAddress(segments2, 4, 6);
		assertEquals("abcdeaaaaa", binaryString1.toString());
		assertEquals("abcdeb", binaryString2.toString());
		assertEquals(-1, binaryString1.compareTo(binaryString2));
		assertEquals(1, binaryString2.compareTo(binaryString1));

	}

	@Test
	public void concatTest() {
		assertEquals(empty, concat());
		assertEquals(null, concat((BinaryString) null));
		assertEquals(empty, concat(empty));
		assertEquals(fromString("ab"), concat(fromString("ab")));
		assertEquals(fromString("ab"), concat(fromString("a"), fromString("b")));
		assertEquals(fromString("abc"), concat(fromString("a"), fromString("b"), fromString("c")));
		assertEquals(null, concat(fromString("a"), null, fromString("c")));
		assertEquals(null, concat(fromString("a"), null, null));
		assertEquals(null, concat(null, null, null));
		assertEquals(fromString("????????????"), concat(fromString("??????"), fromString("??????")));
	}

	@Test
	public void concatWsTest() {
		// Returns empty if the separator is null
		assertEquals(null, concatWs(null, (BinaryString) null));
		assertEquals(null, concatWs(null, fromString("a")));

		// If separator is null, concatWs should skip all null inputs and never return null.
		BinaryString sep = fromString("??????");
		assertEquals(
			empty,
			concatWs(sep, empty));
		assertEquals(
			fromString("ab"),
			concatWs(sep, fromString("ab")));
		assertEquals(
			fromString("a??????b"),
			concatWs(sep, fromString("a"), fromString("b")));
		assertEquals(
			fromString("a??????b??????c"),
			concatWs(sep, fromString("a"), fromString("b"), fromString("c")));
		assertEquals(
			fromString("a??????c"),
			concatWs(sep, fromString("a"), null, fromString("c")));
		assertEquals(
			fromString("a"),
			concatWs(sep, fromString("a"), null, null));
		assertEquals(
			empty,
			concatWs(sep, null, null, null));
		assertEquals(
			fromString("??????????????????"),
			concatWs(sep, fromString("??????"), fromString("??????")));
	}

	@Test
	public void contains() {
		assertTrue(empty.contains(empty));
		assertTrue(fromString("hello").contains(fromString("ello")));
		assertFalse(fromString("hello").contains(fromString("vello")));
		assertFalse(fromString("hello").contains(fromString("hellooo")));
		assertTrue(fromString("????????????").contains(fromString("?????????")));
		assertFalse(fromString("????????????").contains(fromString("??????")));
		assertFalse(fromString("????????????").contains(fromString("???????????????")));
	}

	@Test
	public void startsWith() {
		assertTrue(empty.startsWith(empty));
		assertTrue(fromString("hello").startsWith(fromString("hell")));
		assertFalse(fromString("hello").startsWith(fromString("ell")));
		assertFalse(fromString("hello").startsWith(fromString("hellooo")));
		assertTrue(fromString("????????????").startsWith(fromString("??????")));
		assertFalse(fromString("????????????").startsWith(fromString("???")));
		assertFalse(fromString("????????????").startsWith(fromString("???????????????")));
	}

	@Test
	public void endsWith() {
		assertTrue(empty.endsWith(empty));
		assertTrue(fromString("hello").endsWith(fromString("ello")));
		assertFalse(fromString("hello").endsWith(fromString("ellov")));
		assertFalse(fromString("hello").endsWith(fromString("hhhello")));
		assertTrue(fromString("????????????").endsWith(fromString("??????")));
		assertFalse(fromString("????????????").endsWith(fromString("???")));
		assertFalse(fromString("????????????").endsWith(fromString("??????????????????")));
	}

	@Test
	public void substring() {
		assertEquals(empty, fromString("hello").substring(0, 0));
		assertEquals(fromString("el"), fromString("hello").substring(1, 3));
		assertEquals(fromString("???"), fromString("????????????").substring(0, 1));
		assertEquals(fromString("??????"), fromString("????????????").substring(1, 3));
		assertEquals(fromString("???"), fromString("????????????").substring(3, 5));
		assertEquals(fromString("?????"), fromString("?????").substring(0, 2));
	}

	@Test
	public void trims() {
		assertEquals(fromString("1"), fromString("1").trim());

		assertEquals(fromString("hello"), fromString("  hello ").trim());
		assertEquals(fromString("hello "), trimLeft(fromString("  hello ")));
		assertEquals(fromString("  hello"), trimRight(fromString("  hello ")));

		assertEquals(fromString("  hello "),
				trim(fromString("  hello "), false, false, fromString(" ")));
		assertEquals(fromString("hello"),
				trim(fromString("  hello "), true, true, fromString(" ")));
		assertEquals(fromString("hello "),
				trim(fromString("  hello "), true, false, fromString(" ")));
		assertEquals(fromString("  hello"),
				trim(fromString("  hello "), false, true, fromString(" ")));
		assertEquals(fromString("hello"),
				trim(fromString("xxxhellox"), true, true, fromString("x")));

		assertEquals(fromString("ell"),
				trim(fromString("xxxhellox"), fromString("xoh")));

		assertEquals(fromString("ellox"),
				trimLeft(fromString("xxxhellox"), fromString("xoh")));

		assertEquals(fromString("xxxhell"),
				trimRight(fromString("xxxhellox"), fromString("xoh")));

		assertEquals(empty, empty.trim());
		assertEquals(empty, fromString("  ").trim());
		assertEquals(empty, trimLeft(fromString("  ")));
		assertEquals(empty, trimRight(fromString("  ")));

		assertEquals(fromString("????????????"), fromString("  ???????????? ").trim());
		assertEquals(fromString("???????????? "), trimLeft(fromString("  ???????????? ")));
		assertEquals(fromString("  ????????????"), trimRight(fromString("  ???????????? ")));

		assertEquals(fromString("????????????"), fromString("????????????").trim());
		assertEquals(fromString("????????????"), trimLeft(fromString("????????????")));
		assertEquals(fromString("????????????"), trimRight(fromString("????????????")));

		assertEquals(fromString(","), trim(fromString("????????????, ????????????"), fromString("?????? ")));
		assertEquals(fromString(", ????????????"),
				trimLeft(fromString("????????????, ????????????"), fromString("?????? ")));
		assertEquals(fromString("????????????,"),
				trimRight(fromString("????????????, ????????????"), fromString("?????? ")));

		char[] charsLessThan0x20 = new char[10];
		Arrays.fill(charsLessThan0x20, (char) (' ' - 1));
		String stringStartingWithSpace =
			new String(charsLessThan0x20) + "hello" + new String(charsLessThan0x20);
		assertEquals(fromString(stringStartingWithSpace), fromString(stringStartingWithSpace).trim());
		assertEquals(fromString(stringStartingWithSpace),
				trimLeft(fromString(stringStartingWithSpace)));
		assertEquals(fromString(stringStartingWithSpace),
				trimRight(fromString(stringStartingWithSpace)));
	}

	@Test
	public void testSqlSubstring() {
		assertEquals(fromString("ello"), substringSQL(fromString("hello"), 2));
		assertEquals(fromString("ell"), substringSQL(fromString("hello"), 2, 3));
		assertEquals(empty, substringSQL(empty, 2, 3));
		assertNull(substringSQL(fromString("hello"), 0, -1));
		assertEquals(empty, substringSQL(fromString("hello"), 10));
		assertEquals(fromString("hel"), substringSQL(fromString("hello"), 0, 3));
		assertEquals(fromString("lo"), substringSQL(fromString("hello"), -2, 3));
		assertEquals(empty, substringSQL(fromString("hello"), -100, 3));
	}

	@Test
	public void reverseTest() {
		assertEquals(fromString("olleh"), reverse(fromString("hello")));
		assertEquals(fromString("??????"), reverse(fromString("??????")));
		assertEquals(fromString("?????? ,olleh"), reverse(fromString("hello, ??????")));
		assertEquals(empty, reverse(empty));
	}

	@Test
	public void indexOf() {
		assertEquals(0, empty.indexOf(empty, 0));
		assertEquals(-1, empty.indexOf(fromString("l"), 0));
		assertEquals(0, fromString("hello").indexOf(empty, 0));
		assertEquals(2, fromString("hello").indexOf(fromString("l"), 0));
		assertEquals(3, fromString("hello").indexOf(fromString("l"), 3));
		assertEquals(-1, fromString("hello").indexOf(fromString("a"), 0));
		assertEquals(2, fromString("hello").indexOf(fromString("ll"), 0));
		assertEquals(-1, fromString("hello").indexOf(fromString("ll"), 4));
		assertEquals(1, fromString("????????????").indexOf(fromString("??????"), 0));
		assertEquals(-1, fromString("????????????").indexOf(fromString("???"), 3));
		assertEquals(0, fromString("????????????").indexOf(fromString("???"), 0));
		assertEquals(3, fromString("????????????").indexOf(fromString("???"), 0));
	}

	@Test
	public void testToNumeric() {
		// Test to integer.
		assertEquals(Byte.valueOf("123"), toByte(fromString("123")));
		assertEquals(Byte.valueOf("123"), toByte(fromString("+123")));
		assertEquals(Byte.valueOf("-123"), toByte(fromString("-123")));

		assertEquals(Short.valueOf("123"), toShort(fromString("123")));
		assertEquals(Short.valueOf("123"), toShort(fromString("+123")));
		assertEquals(Short.valueOf("-123"), toShort(fromString("-123")));

		assertEquals(Integer.valueOf("123"), toInt(fromString("123")));
		assertEquals(Integer.valueOf("123"), toInt(fromString("+123")));
		assertEquals(Integer.valueOf("-123"), toInt(fromString("-123")));

		assertEquals(Long.valueOf("1234567890"),
				toLong(fromString("1234567890")));
		assertEquals(Long.valueOf("+1234567890"),
				toLong(fromString("+1234567890")));
		assertEquals(Long.valueOf("-1234567890"),
				toLong(fromString("-1234567890")));

		// Test decimal string to integer.
		assertEquals(Integer.valueOf("123"), toInt(fromString("123.456789")));
		assertEquals(Long.valueOf("123"), toLong(fromString("123.456789")));

		// Test negative cases.
		assertNull(toInt(fromString("1a3.456789")));
		assertNull(toInt(fromString("123.a56789")));

		// Test composite in BinaryRow.
		BinaryRow row = new BinaryRow(20);
		BinaryRowWriter writer = new BinaryRowWriter(row);
		writer.writeString(0, BinaryString.fromString("1"));
		writer.writeString(1, BinaryString.fromString("123"));
		writer.writeString(2, BinaryString.fromString("12345"));
		writer.writeString(3, BinaryString.fromString("123456789"));
		writer.complete();

		assertEquals(Byte.valueOf("1"), toByte(row.getString(0)));
		assertEquals(Short.valueOf("123"), toShort(row.getString(1)));
		assertEquals(Integer.valueOf("12345"), toInt(row.getString(2)));
		assertEquals(Long.valueOf("123456789"), toLong(row.getString(3)));
	}

	@Test
	public void testToUpperLowerCase() {
		assertEquals(fromString("???????????????"),
			fromString("???????????????").toLowerCase());
		assertEquals(fromString("???????????????"),
			fromString("???????????????").toUpperCase());

		assertEquals(fromString("abcdefg"),
			fromString("aBcDeFg").toLowerCase());
		assertEquals(fromString("ABCDEFG"),
			fromString("aBcDeFg").toUpperCase());

		assertEquals(fromString("!@#$%^*"),
			fromString("!@#$%^*").toLowerCase());
		assertEquals(fromString("!@#$%^*"),
			fromString("!@#$%^*").toLowerCase());
		// Test composite in BinaryRow.
		BinaryRow row = new BinaryRow(20);
		BinaryRowWriter writer = new BinaryRowWriter(row);
		writer.writeString(0, BinaryString.fromString("a"));
		writer.writeString(1, BinaryString.fromString("???????????????"));
		writer.writeString(3, BinaryString.fromString("aBcDeFg"));
		writer.writeString(5, BinaryString.fromString("!@#$%^*"));
		writer.complete();

		assertEquals(fromString("A"), row.getString(0).toUpperCase());
		assertEquals(fromString("???????????????"), row.getString(1).toUpperCase());
		assertEquals(fromString("???????????????"), row.getString(1).toLowerCase());
		assertEquals(fromString("ABCDEFG"), row.getString(3).toUpperCase());
		assertEquals(fromString("abcdefg"), row.getString(3).toLowerCase());
		assertEquals(fromString("!@#$%^*"), row.getString(5).toUpperCase());
		assertEquals(fromString("!@#$%^*"), row.getString(5).toLowerCase());
	}

	@Test
	public void testToDecimal() {
		class DecimalData {
			private String str;
			private int precision, scale;

			private DecimalData(String str, int precision, int scale) {
				this.str = str;
				this.precision = precision;
				this.scale = scale;
			}
		}

		DecimalData[] data = {
			new DecimalData("12.345", 5, 3),
			new DecimalData("-12.345", 5, 3),
			new DecimalData("+12345", 5, 0),
			new DecimalData("-12345", 5, 0),
			new DecimalData("12345.", 5, 0),
			new DecimalData("-12345.", 5, 0),
			new DecimalData(".12345", 5, 5),
			new DecimalData("-.12345", 5, 5),
			new DecimalData("+12.345E3", 5, 0),
			new DecimalData("-12.345e3", 5, 0),
			new DecimalData("12.345e-3", 6, 6),
			new DecimalData("-12.345E-3", 6, 6),
			new DecimalData("12345E3", 8, 0),
			new DecimalData("-12345e3", 8, 0),
			new DecimalData("12345e-3", 5, 3),
			new DecimalData("-12345E-3", 5, 3),
			new DecimalData("+.12345E3", 5, 2),
			new DecimalData("-.12345e3", 5, 2),
			new DecimalData(".12345e-3", 8, 8),
			new DecimalData("-.12345E-3", 8, 8),
			new DecimalData("1234512345.1234", 18, 8),
			new DecimalData("-1234512345.1234", 18, 8),
			new DecimalData("1234512345.1234", 12, 2),
			new DecimalData("-1234512345.1234", 12, 2),
			new DecimalData("1234512345.1299", 12, 2),
			new DecimalData("-1234512345.1299", 12, 2),
			new DecimalData("999999999999999999", 18, 0),
			new DecimalData("1234512345.1234512345", 20, 10),
			new DecimalData("-1234512345.1234512345", 20, 10),
			new DecimalData("1234512345.1234512345", 15, 5),
			new DecimalData("-1234512345.1234512345", 15, 5),
			new DecimalData("12345123451234512345E-10", 20, 10),
			new DecimalData("-12345123451234512345E-10", 20, 10),
			new DecimalData("12345123451234512345E-10", 15, 5),
			new DecimalData("-12345123451234512345E-10", 15, 5),
			new DecimalData("999999999999999999999", 21, 0),
			new DecimalData("-999999999999999999999", 21, 0),
			new DecimalData("0.00000000000000000000123456789123456789", 38, 38),
			new DecimalData("-0.00000000000000000000123456789123456789", 38, 38),
			new DecimalData("0.00000000000000000000123456789123456789", 29, 29),
			new DecimalData("-0.00000000000000000000123456789123456789", 29, 29),
			new DecimalData("123456789123E-27", 18, 18),
			new DecimalData("-123456789123E-27", 18, 18),
			new DecimalData("123456789999E-27", 18, 18),
			new DecimalData("-123456789999E-27", 18, 18),
			new DecimalData("123456789123456789E-36", 18, 18),
			new DecimalData("-123456789123456789E-36", 18, 18),
			new DecimalData("123456789999999999E-36", 18, 18),
			new DecimalData("-123456789999999999E-36", 18, 18)
		};

		for (DecimalData d : data) {
			assertEquals(
				Decimal.fromBigDecimal(new BigDecimal(d.str), d.precision, d.scale),
				toDecimal(fromString(d.str), d.precision, d.scale));
		}

		BinaryRow row = new BinaryRow(data.length);
		BinaryRowWriter writer = new BinaryRowWriter(row);
		for (int i = 0; i < data.length; i++) {
			writer.writeString(i, BinaryString.fromString(data[i].str));
		}
		writer.complete();
		for (int i = 0; i < data.length; i++) {
			DecimalData d = data[i];
			assertEquals(
				Decimal.fromBigDecimal(new BigDecimal(d.str), d.precision, d.scale),
					toDecimal(row.getString(i), d.precision, d.scale));
		}
	}

	@Test
	public void testEmptyString() {
		BinaryString str2 = fromString("hahahahah");
		BinaryString str3 = new BinaryString();
		{
			MemorySegment[] segments = new MemorySegment[2];
			segments[0] = MemorySegmentFactory.wrap(new byte[10]);
			segments[1] = MemorySegmentFactory.wrap(new byte[10]);
			str3.pointTo(segments, 15, 0);
		}

		assertTrue(BinaryString.EMPTY_UTF8.compareTo(str2) < 0);
		assertTrue(str2.compareTo(BinaryString.EMPTY_UTF8) > 0);

		assertTrue(BinaryString.EMPTY_UTF8.compareTo(str3) == 0);
		assertTrue(str3.compareTo(BinaryString.EMPTY_UTF8) == 0);

		assertFalse(BinaryString.EMPTY_UTF8.equals(str2));
		assertFalse(str2.equals(BinaryString.EMPTY_UTF8));

		assertTrue(BinaryString.EMPTY_UTF8.equals(str3));
		assertTrue(str3.equals(BinaryString.EMPTY_UTF8));
	}

	@Test
	public void testEncodeWithIllegalCharacter() throws UnsupportedEncodingException {

		// Tis char array has some illegal character, such as 55357
		// the jdk ignores theses character and cast them to '?'
		// which StringUtf8Utils'encodeUTF8 should follow
		char[] chars = new char[] { 20122, 40635, 124, 38271, 34966,
			124, 36830, 34915, 35033, 124, 55357, 124, 56407 };

		String str = new String(chars);

		assertArrayEquals(
			str.getBytes("UTF-8"),
			StringUtf8Utils.encodeUTF8(str)
		);

	}

	@Test
	public void testKeyValue() {
		assertNull(keyValue(fromString("k1:v1|k2:v2"),
			fromString("|").byteAt(0),
			fromString(":").byteAt(0),
			fromString("k3")));
		assertNull(keyValue(fromString("k1:v1|k2:v2|"),
			fromString("|").byteAt(0),
			fromString(":").byteAt(0),
			fromString("k3")));
		assertNull(keyValue(fromString("|k1:v1|k2:v2|"),
			fromString("|").byteAt(0),
			fromString(":").byteAt(0),
			fromString("k3")));
		String tab = org.apache.commons.lang3.StringEscapeUtils.unescapeJava("\t");
		assertEquals(fromString("v2"),
				keyValue(fromString("k1:v1" + tab + "k2:v2"),
				fromString("\t").byteAt(0),
				fromString(":").byteAt(0),
				fromString("k2")));
		assertNull(keyValue(fromString("k1:v1|k2:v2"),
			fromString("|").byteAt(0),
			fromString(":").byteAt(0),
			null));
		assertEquals(fromString("v2"),
				keyValue(fromString("k1=v1;k2=v2"),
				fromString(";").byteAt(0),
				fromString("=").byteAt(0),
				fromString("k2")));
		assertEquals(fromString("v2"),
				keyValue(fromString("|k1=v1|k2=v2|"),
				fromString("|").byteAt(0),
				fromString("=").byteAt(0),
				fromString("k2")));
		assertEquals(fromString("v2"),
				keyValue(fromString("k1=v1||k2=v2"),
				fromString("|").byteAt(0),
				fromString("=").byteAt(0),
				fromString("k2")));
		assertNull(keyValue(fromString("k1=v1;k2"),
			fromString(";").byteAt(0),
			fromString("=").byteAt(0),
			fromString("k2")));
		assertNull(keyValue(fromString("k1;k2=v2"),
			fromString(";").byteAt(0),
			fromString("=").byteAt(0),
			fromString("k1")));
		assertNull(keyValue(fromString("k=1=v1;k2=v2"),
			fromString(";").byteAt(0),
			fromString("=").byteAt(0),
			fromString("k=")));
		assertEquals(fromString("=v1"),
				keyValue(fromString("k1==v1;k2=v2"),
				fromString(";").byteAt(0),
				fromString("=").byteAt(0), fromString("k1")));
		assertNull(keyValue(fromString("k1==v1;k2=v2"),
			fromString(";").byteAt(0),
			fromString("=").byteAt(0), fromString("k1=")));
		assertNull(keyValue(fromString("k1=v1;k2=v2"),
			fromString(";").byteAt(0),
			fromString("=").byteAt(0),
			fromString("k1=")));
		assertNull(keyValue(fromString("k1k1=v1;k2=v2"),
			fromString(";").byteAt(0),
			fromString("=").byteAt(0),
			fromString("k1")));
		assertNull(keyValue(fromString("k1=v1;k2=v2"),
			fromString(";").byteAt(0),
			fromString("=").byteAt(0),
			fromString("k1k1k1k1k1k1k1k1k1k1")));
		assertEquals(fromString("v2"),
				keyValue(fromString("k1:v||k2:v2"),
				fromString("|").byteAt(0),
				fromString(":").byteAt(0),
				fromString("k2")));
		assertEquals(fromString("v2"),
				keyValue(fromString("k1:v||k2:v2"),
				fromString("|").byteAt(0),
				fromString(":").byteAt(0),
				fromString("k2")));
	}

	@Test
	public void testDecodeWithIllegalUtf8Bytes() throws UnsupportedEncodingException {

		// illegal utf-8 bytes
		byte[] bytes = new byte[] {(byte) 20122, (byte) 40635, 124, (byte) 38271, (byte) 34966,
			124, (byte) 36830, (byte) 34915, (byte) 35033, 124, (byte) 55357, 124, (byte) 56407 };

		String str = new String(bytes, StandardCharsets.UTF_8);
		assertEquals(str, StringUtf8Utils.decodeUTF8(bytes, 0, bytes.length));
		assertEquals(str, StringUtf8Utils.decodeUTF8(MemorySegmentFactory.wrap(bytes), 0, bytes.length));

		byte[] newBytes = new byte[bytes.length + 5];
		System.arraycopy(bytes, 0, newBytes, 5, bytes.length);
		assertEquals(str, StringUtf8Utils.decodeUTF8(MemorySegmentFactory.wrap(newBytes), 5, bytes.length));
	}

	@Test
	public void skipWrongFirstByte() {
		int[] wrongFirstBytes = {
			0x80, 0x9F, 0xBF, // Skip Continuation bytes
			0xC0, 0xC2, // 0xC0..0xC1 - disallowed in UTF-8
			// 0xF5..0xFF - disallowed in UTF-8
			0xF5, 0xF6, 0xF7, 0xF8, 0xF9,
			0xFA, 0xFB, 0xFC, 0xFD, 0xFE, 0xFF
		};
		byte[] c = new byte[1];

		for (int wrongFirstByte : wrongFirstBytes) {
			c[0] = (byte) wrongFirstByte;
			assertEquals(fromBytes(c).numChars(), 1);
		}
	}

	@Test
	public void testSplit() {
		assertArrayEquals(EMPTY_STRING_ARRAY,
				splitByWholeSeparatorPreserveAllTokens(fromString(""), fromString("")));
		assertArrayEquals(new BinaryString[] {fromString("ab"), fromString("de"), fromString("fg")},
				splitByWholeSeparatorPreserveAllTokens(fromString("ab de fg"), null));
		assertArrayEquals(new BinaryString[] {fromString("ab"), fromString(""), fromString(""),
						fromString("de"), fromString("fg")},
				splitByWholeSeparatorPreserveAllTokens(fromString("ab   de fg"), null));
		assertArrayEquals(new BinaryString[] {fromString("ab"), fromString("cd"), fromString("ef")},
				splitByWholeSeparatorPreserveAllTokens(fromString("ab:cd:ef"), fromString(":")));
		assertArrayEquals(new BinaryString[] {fromString("ab"), fromString("cd"), fromString("ef")},
				splitByWholeSeparatorPreserveAllTokens(fromString("ab-!-cd-!-ef"), fromString("-!-")));
	}

	@Test
	public void testLazy() {
		String javaStr = "haha";
		BinaryString str = BinaryString.fromString(javaStr);
		str.ensureMaterialized();

		// check reference same.
		assertSame(str.toString(), javaStr);
	}
}
