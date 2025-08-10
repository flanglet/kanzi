/*
Copyright 2011-2025 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at
                http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package io.github.flanglet.kanzi;


/**
 * This class defines constants representing magic numbers for various file types
 * and provides methods to identify file types based on their magic numbers.
 */
public class Magic {

    /**
     * Represents no magic number, indicating an unknown or unidentifiable file type.
     */
    public static final int NO_MAGIC = 0;
    /**
     * Magic number for JPEG image files.
     */
    public static final int JPG_MAGIC = 0xFFD8FFE0;
    /**
     * Magic number for GIF image files.
     */
    public static final int GIF_MAGIC = 0x47494638;
    /**
     * Magic number for PDF document files.
     */
    public static final int PDF_MAGIC = 0x25504446;
    /**
     * Magic number for ZIP archive files (also applies to JAR and Office documents).
     */
    public static final int ZIP_MAGIC = 0x504B0304; // Works for jar & office docs
    /**
     * Magic number for LZMA compressed files (also applies to 7z archives).
     */
    public static final int LZMA_MAGIC = 0x377ABCAF; // Works for 7z  37 7A BC AF 27 1C
    /**
     * Magic number for PNG image files.
     */
    public static final int PNG_MAGIC = 0x89504E47;
    /**
     * Magic number for ELF (Executable and Linkable Format) executable files.
     */
    public static final int ELF_MAGIC = 0x7F454C46;
    /**
     * Magic number for 32-bit Mach-O executable files (macOS).
     */
    public static final int MAC_MAGIC32 = 0xFEEDFACE;
    /**
     * Magic number for 32-bit Mach-O executable files (reversed byte order).
     */
    public static final int MAC_CIGAM32 = 0xCEFAEDFE;
    /**
     * Magic number for 64-bit Mach-O executable files (macOS).
     */
    public static final int MAC_MAGIC64 = 0xFEEDFACF;
    /**
     * Magic number for 64-bit Mach-O executable files (reversed byte order).
     */
    public static final int MAC_CIGAM64 = 0xCFFAEDFE;
    /**
     * Magic number for Zstandard compressed files.
     */
    public static final int ZSTD_MAGIC = 0x28B52FFD;
    /**
     * Magic number for Brotli compressed files.
     */
    public static final int BROTLI_MAGIC = 0x81CFB2CE;
    /**
     * Magic number for RIFF (Resource Interchange File Format) files (e.g., WAV, AVI, WEBP).
     */
    public static final int RIFF_MAGIC = 0x52494646; // WAV, AVI, WEBP
    /**
     * Magic number for Microsoft Cabinet files.
     */
    public static final int CAB_MAGIC = 0x4D534346;
    /**
     * Magic number for FLAC (Free Lossless Audio Codec) files.
     */
    public static final int FLAC_MAGIC = 0x664C6143;
    /**
     * Magic number for XZ compressed files.
     */
    public static final int XZ_MAGIC = 0xFD377A58; // FD 37 7A 58 5A 00
    /**
     * Magic number for RAR archive files.
     */
    public static final int RAR_MAGIC = 0x52617221; // 52 61 72 21 1A 07 00
    /**
     * Magic number for Kanzi compressed files.
     */
    public static final int KNZ_MAGIC = 0x4B414E5A;
    /**
     * Magic number for BZIP2 compressed files.
     */
    public static final int BZIP2_MAGIC = 0x425A68;
    /**
     * Magic number for MP3 files with an ID3 tag.
     */
    public static final int MP3_ID3_MAGIC = 0x494433;
    /**
     * Magic number for GZIP compressed files.
     */
    public static final int GZIP_MAGIC = 0x1F8B;
    /**
     * Magic number for BMP image files.
     */
    public static final int BMP_MAGIC = 0x424D;
    /**
     * Magic number for Windows executable files.
     */
    public static final int WIN_MAGIC = 0x4D5A;
    /**
     * Magic number for PBM (Portable BitMap) image files (binary only).
     */
    public static final int PBM_MAGIC = 0x5034; // bin only
    /**
     * Magic number for PGM (Portable GrayMap) image files (binary only).
     */
    public static final int PGM_MAGIC = 0x5035; // bin only
    /**
     * Magic number for PPM (Portable PixMap) image files (binary only).
     */
    public static final int PPM_MAGIC = 0x5036; // bin only

    private static final int[] KEYS32 = {
        GIF_MAGIC, PDF_MAGIC, ZIP_MAGIC, LZMA_MAGIC, PNG_MAGIC,
        ELF_MAGIC, MAC_MAGIC32, MAC_CIGAM32, MAC_MAGIC64, MAC_CIGAM64,
        ZSTD_MAGIC, BROTLI_MAGIC, CAB_MAGIC, RIFF_MAGIC, FLAC_MAGIC,
        XZ_MAGIC, KNZ_MAGIC, RAR_MAGIC
    };

    private static final int[] KEYS16 = {
        GZIP_MAGIC, BMP_MAGIC, WIN_MAGIC
    };

    /**
     * Identifies the type of a file based on its magic number.
     *
     * @param src the byte array containing the file data
     * @param start the starting index in the array
     * @return the magic number of the file type
     */
    public static int getType(byte[] src, int start) {
        if (src.length < 4)
            return NO_MAGIC;

        final int key = Memory.BigEndian.readInt32(src, start);
        if ((key & ~0x0F) == JPG_MAGIC)
            return key;

        if (((key >> 8) == BZIP2_MAGIC) || ((key >> 8) == MP3_ID3_MAGIC))
            return key >> 8;

        for (int i = 0; i < KEYS32.length; i++) {
            if (key == KEYS32[i])
                return key;
        }

        final int key16 = key >> 16;
        for (int i = 0; i < KEYS16.length; i++) {
            if (key16 == KEYS16[i])
                return key16;
        }

        if ((key16 == PBM_MAGIC) || (key16 == PGM_MAGIC) || (key16 == PPM_MAGIC)) {
            final int subkey = (key >> 8) & 0xFF;
            if ((subkey == 0x07) || (subkey == 0x0A) || (subkey == 0x0D) || (subkey == 0x20))
                return key16;
        }

        return NO_MAGIC;
    }

    /**
     * Checks if a file type represented by its magic number is compressed.
     *
     * @param magic the magic number of the file type
     * @return {@code true} if the file type is compressed, {@code false} otherwise
     */
    public static boolean isCompressed(int magic) {
        switch (magic) {
            case JPG_MAGIC:
            case GIF_MAGIC:
            case PNG_MAGIC:
            // case RIFF_MAGIC: may or may not be
            case LZMA_MAGIC:
            case ZSTD_MAGIC:
            case BROTLI_MAGIC:
            case CAB_MAGIC:
            case ZIP_MAGIC:
            case GZIP_MAGIC:
            case BZIP2_MAGIC:
            case FLAC_MAGIC:
            case MP3_ID3_MAGIC:
            case XZ_MAGIC:
            case KNZ_MAGIC:
            case RAR_MAGIC:
                return true;
            default:
                return false;
        }
    }

    /**
     * Checks if a file type represented by its magic number is multimedia.
     *
     * @param magic the magic number of the file type
     * @return {@code true} if the file type is multimedia, {@code false} otherwise
     */
    public static boolean isMultimedia(int magic) {
        switch (magic) {
            case JPG_MAGIC:
            case GIF_MAGIC:
            case PNG_MAGIC:
            case RIFF_MAGIC:
            case FLAC_MAGIC:
            case MP3_ID3_MAGIC:
            case BMP_MAGIC:
            case PBM_MAGIC:
            case PGM_MAGIC:
            case PPM_MAGIC:
                return true;
            default:
                return false;
        }
    }

    /**
     * Checks if a file type represented by its magic number is executable.
     *
     * @param magic the magic number of the file type
     * @return {@code true} if the file type is executable, {@code false} otherwise
     */
    public static boolean isExecutable(int magic) {
        switch (magic) {
            case ELF_MAGIC:
            case WIN_MAGIC:
            case MAC_MAGIC32:
            case MAC_CIGAM32:
            case MAC_MAGIC64:
            case MAC_CIGAM64:
                return true;
            default:
                return false;
        }
    }
}
