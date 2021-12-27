/*-
 * Copyright 2019 Mariusz Zaborski <oshogbo@FreeBSD.org>
 *
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
 */

package lx;

import java.io.IOException;
import java.util.Iterator;

import ghidra.app.util.bin.BinaryReader;

public class LX {
	private long base_addr;
	private LXHeader header;
	private LXObjectTable []object_table;
	private LXObjectPageTable []object_page_table;
	private LXFixupPageTable []fixup_page_table;

	protected LXObjectTable[] loadObjectTable(BinaryReader reader) throws IOException {
		/*
		 * [Doc]
		 * public long object_table_offset;		 40h
		 * public long objects_in_module;		 44h
		 */
		LXObjectTable []opt = new LXObjectTable[(int)header.objects_in_module];

		reader.setPointerIndex(base_addr + header.object_table_offset);
		for (int i = 0; i < (int)header.objects_in_module; i++) {
			opt[i] = new LXObjectTable(reader);
		}

		return opt;
	}

	protected LXObjectPageTable[] loadObjectPageTable(BinaryReader reader) throws IOException {
		/*
		 * [Doc]
		 * public long	module_of_pages;		14h
		 * public long object_iter_page_offset;		4ch
		 */
		LXObjectPageTable []opt = new LXObjectPageTable[(int)header.module_of_pages];

		reader.setPointerIndex(base_addr + header.object_page_table_offset);
		for (int i = 0; i < (int)header.module_of_pages; i++) {
			opt[i] = new LXObjectPageTable(reader, header.isLe());
		}

		return opt;
	}

	protected LXFixupPageTable[] loadFixupPageTable(BinaryReader reader) throws IOException {
		/*
		 * [Doc]
		 * This table is parallel to the Object Page Table, except that
		 * there is one  additional entry in this table to indicate the
		 * end of the Fixup Record Table.
		 * public long	module_of_pages;		14h
		 *
		 * public long fixup_page_table_offset;		68h
		 */
		LXFixupPageTable []fpt = new LXFixupPageTable[(int)header.module_of_pages + 1];

		reader.setPointerIndex(base_addr + header.fixup_page_table_offset);
		for (int i = 0; i < (int)header.module_of_pages + 1; i++) {
			fpt[i] = new LXFixupPageTable(reader);
		}

		return fpt;
	}

	protected void loadFixupRecordTable(BinaryReader reader) throws IOException {
		/*
		 * [Doc]
		 * public long fixup_record_table_offset;	6Ch
		 */
		LXFixupRecordTable frt;
		LXObjectTable ot;
		long start, end, base_offset;
		int page_end_i;

		for (int i = 0; i < (int)header.objects_in_module; i++) {
			ot = getLXObjectTable(i);
			page_end_i = getPageEndIndex(ot);

			for (int oi = (int)ot.page_table_index; oi < page_end_i; oi++) {
				start = base_addr + header.fixup_record_table_offset + fixup_page_table[oi].offset;
				end = base_addr + header.fixup_record_table_offset + fixup_page_table[oi + 1].offset;
				base_offset = (oi - ot.page_table_index) * header.page_size;

				reader.setPointerIndex(start);
				while (start < end) {
					frt = new LXFixupRecordTable(reader, base_offset);
					ot.appendFixupTable(frt);
					start += frt.getSizeInFile();
				}
			}
		}
	}

	public LX(BinaryReader reader, long base_addr, long exeoffset) throws IOException {
		this.base_addr = base_addr;
		reader.setPointerIndex(base_addr);

		header = new LXHeader(reader);
		/*
		 * data_pages_offset contains the offset relative to the exe start
		 * in an MZ/LE, add the offset to the beginning of the embedded exe.
		 */
		header.data_pages_offset += exeoffset;
		
		object_table = loadObjectTable(reader);
		object_page_table = loadObjectPageTable(reader);
		fixup_page_table = loadFixupPageTable(reader);
		loadFixupRecordTable(reader);
	}

	public LXHeader getHeader() {
		return header;
	}

	public LXObjectTable getLXObjectTable(int i) {
		return object_table[i];
	}

	public LXObjectPageTable getLXObjectPageTable(int oi) {
		return object_page_table[oi];
	}

	public long sizeOfLXObjectTable() {
		return object_table.length;
	}

	private long getPageFileOffset(int oi) {
		LXObjectPageTable opt = getLXObjectPageTable(oi);

		if (header.isLe())
			return (opt.page_num-1) * header.page_size + header.data_pages_offset;

		return (opt.page_data_offset + opt.data_size - 1) *
				header.page_size + header.data_pages_offset;
	}

	private long getPageFileSize(LXObjectTable ohdr, int oi, long datapos) {
		assert(oi + 1 <= header.module_of_pages);

		if (oi + 1 == header.module_of_pages)
			return Math.min(ohdr.virtual_size - datapos, header.page_offset_shift);
		return Math.min(ohdr.virtual_size - datapos, header.page_size);
	}

	private int getPageEndIndex(LXObjectTable ohdr) {
		return (int)Math.min(ohdr.page_table_index + ohdr.page_table_entries, header.module_of_pages);
	}

	/* Can I do it better in java? ... */
	private void emitU16(byte []data, int offset, long val) {
		data[offset] = (byte)((val) & 0xFF);
		data[offset + 1] = (byte)((val >> 8) & 0xFF);
	}
	private void emitU32(byte []data, int offset, long val) {
		data[offset] = (byte)((val) & 0xFF);
		data[offset + 1] = (byte)((val >> 8) & 0xFF);
		data[offset + 2] = (byte)((val >> 16) & 0xFF);
		data[offset + 3] = (byte)((val >> 24) & 0xFF);
	}
	/* ... Probably */

	private void applyFixups(LXObjectTable ot, byte []data) {
		Iterator<LXFixupRecordTable> itr = ot.fixupTableIterator();
		LXFixupRecordTable frt;
		long memAddr;

		while (itr.hasNext()) {
			frt = itr.next();

			if (frt.getSourceType() == 0x02) {
				/* XXX: What supposed should I do? */
				continue;
			}

			memAddr = getLXObjectTable((int)frt.object).reloc_base_addr + frt.trgoff;

			for (int i = 0; i < frt.getDSTOffsetCount(); i++) {
				if (data.length < frt.getDSTOffset(i)) {
					/* XXX: What supposed should I do? */
					/*
					 * [DOC]
					 * Note that for fixups that  cross page  boundaries, a
					 * separate  fixup  record is  specified for each page.
					 * An offset is still used for the 2nd  page but it now
					 * becomes a negative offset since the fixup originated
					 * on  the  preceding page.  (For  example, if only the
					 * last one byte of a 32-bit address is on the page  to
					 * be fixed up, then the offset would  have  a value of
					 * -3.)
					 */
					continue;
				}

				switch (frt.getSourceType()) {
				case 0x05: /* 16-bit */
					emitU16(data, frt.getDSTOffset(i), memAddr);
					break;
				case 0x06: /* 16:32 bit Pointer */
					/* XXX: What todo ? */
					emitU32(data, frt.getDSTOffset(i), memAddr);
					break;
				case 0x07: /* 32-bit */
					emitU32(data, frt.getDSTOffset(i), memAddr);
					break;
				case 0x08: /* 32-bit Self-relative offset fixup */
					emitU32(data, frt.getDSTOffset(i), frt.getDSTOffset(i) + frt.trgoff);
					break;
				}
			}
		}
	}

	public byte[] readObjectData(BinaryReader reader, LXObjectTable ot) throws IOException {
		byte []data = new byte[(int)ot.virtual_size];
		int page_end_i = getPageEndIndex(ot);
		int datapos = 0;

		for (int oi = (int)ot.page_table_index; oi < page_end_i; oi++) {
			int rsize = (int)getPageFileSize(ot, oi, datapos);
			byte []xdata = reader.readByteArray(getPageFileOffset(oi), rsize);

			System.arraycopy(xdata, 0, data, datapos, rsize);
			datapos += rsize;
		}

		applyFixups(ot, data);

		return data;
	}

	public long getEIPAddress() {
		return getLXObjectTable((int)header.eip_object).reloc_base_addr + header.eip;
	}
}
