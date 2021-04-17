/*
Copyright 2011-2021 Frederic Langlet
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

package kanzi.transform;

import java.util.Map;
import kanzi.ByteTransform;
import kanzi.Global;
import kanzi.SliceByteArray;


// Simple one-pass text codec. Uses a default (small) static dictionary
// or potentially larger custom one. Generates a dynamic dictionary.
public final class TextCodec implements ByteTransform
{
   private static final int THRESHOLD1 = 128;
   private static final int THRESHOLD2 = THRESHOLD1 * THRESHOLD1;
   private static final int THRESHOLD3 = 32;
   private static final int THRESHOLD4 = THRESHOLD3 * 128;
   private static final int MAX_DICT_SIZE = 1 << 19;
   private static final int MAX_WORD_LENGTH = 31;
   private static final int MAX_BLOCK_SIZE = 1 << 30; //1 GB
   private static final int LOG_HASHES_SIZE = 24; // 16 MB
   public static final byte LF = 0x0A;
   public static final byte CR = 0x0D;
   public static final byte ESCAPE_TOKEN1 = 0x0F; // dictionary word preceded by space symbol
   public static final byte ESCAPE_TOKEN2 = 0x0E; // toggle upper/lower case of first word char
   private static final int HASH1 = 0x7FEB352D;
   private static final int HASH2 = 0x846CA68B;
   private static final int MASK_NOT_TEXT = 0x80;
   private static final int MASK_DNA = MASK_NOT_TEXT | 0x40;
   private static final int MASK_BIN = MASK_NOT_TEXT | 0x20;
   private static final int MASK_BASE64 = MASK_NOT_TEXT | 0x10;
   private static final int MASK_NUMERIC = MASK_NOT_TEXT | 0x08;
   private static final int MASK_FULL_ASCII = 0x04;
   private static final int MASK_XML_HTML = 0x02;
   private static final int MASK_CRLF = 0x01;
   private static final int MASK_LENGTH = 0x0007FFFF; // 19 bits

   private static final boolean[] DELIMITER_CHARS = initDelimiterChars();

   private static boolean[] initDelimiterChars()
   {
      boolean[] res = new boolean[256];

      for (int i=0; i<256; i++)
      {
         if ((i >= ' ') && (i <= '/')) // [ !"#$%&'()*+,-./]
            res[i] = true;
         else if ((i >= ':') && (i <= '?')) // [:;<=>?]
            res[i] = true;
         else
         {
            switch (i)
            {
               case '\n' :
               case '\t' :
               case '\r' :
               case '_'  :
               case '|'  :
               case '{'  :
               case '}'  :
               case '['  :
               case ']'  :
                 res[i] = true;
                 break;
               default :
                 res[i] = false;
            }
         }
      }

      return res;
   }

   // Default dictionary
   // 1024 of the most common English words with at least 2 chars.
   private static final byte[] DICT_EN_1024 = (
      "TheBeAndOfInToWithItThatForYouHeHaveOnSaidSayAtButWeByHadTheyAsW" +
      "ouldWhoOrCanMayDoThisWasIsMuchAnyFromNotSheWhatTheirWhichGetGive" +
      "HasAreHimHerComeMyOurWereWillSomeBecauseThereThroughTellWhenWork" +
      "ThemYetUpOwnOutIntoJustCouldOverOldThinkDayWayThanLikeOtherHowTh" +
      "enItsPeopleTwoMoreTheseBeenNowWantFirstNewUseSeeTimeManManyThing" +
      "MakeHereWellOnlyHisVeryAfterWithoutAnotherNoAllBelieveBeforeOffT" +
      "houghSoAgainstWhileLastTooDownTodaySameBackTakeEachDifferentWher" +
      "eBetweenThoseEvenSeenUnderAboutOneAlsoFactMustActuallyPreventExp" +
      "ectContainConcernIfSchoolYearGoingCannotDueEverTowardGirlFirmGla" +
      "ssGasKeepWorldStillWentShouldSpendStageDoctorMightJobGoContinueE" +
      "veryoneNeverAnswerFewMeanDifferenceTendNeedLeaveTryNiceHoldSomet" +
      "hingAskWarmLipCoverIssueHappenTurnLookSureDiscoverFightMadDirect" +
      "ionAgreeSomeoneFailRespectNoticeChoiceBeginThreeSystemLevelFeelM" +
      "eetCompanyBoxShowPlayLiveLetterEggNumberOpenProblemFatHandMeasur" +
      "eQuestionCallRememberCertainPutNextChairStartRunRaiseGoalReallyH" +
      "omeTeaCandidateMoneyBusinessYoungGoodCourtFindKnowKindHelpNightC" +
      "hildLotYourUsEyeYesWordBitVanMonthHalfLowMillionHighOrganization" +
      "RedGreenBlueWhiteBlackYourselfEightBothLittleHouseLetDespiteProv" +
      "ideServiceHimselfFriendDescribeFatherDevelopmentAwayKillTripHour" +
      "GameOftenPlantPlaceEndAmongSinceStandDesignParticularSuddenlyMem" +
      "berPayLawBookSilenceAlmostIncludeAgainEitherToolFourOnceLeastExp" +
      "lainIdentifyUntilSiteMinuteCoupleWeekMatterBringDetailInformatio" +
      "nNothingAnythingEverythingAgoLeadSometimesUnderstandWhetherNatur" +
      "eTogetherFollowParentStopIndeedDifficultPublicAlreadySpeakMainta" +
      "inRemainHearAllowMediaOfficeBenefitDoorHugPersonLaterDuringWarHi" +
      "storyArgueWithinSetArticleStationMorningWalkEventWinChooseBehavi" +
      "orShootFireFoodTitleAroundAirTeacherGapSubjectEnoughProveAcrossA" +
      "lthoughHeadFootSecondBoyMainLieAbleCivilTableLoveProcessOfferStu" +
      "dentConsiderAppearStudyBuyNearlyHumanEvidenceTextMethodIncluding" +
      "SendRealizeSenseBuildControlAudienceSeveralCutCollegeInterestSuc" +
      "cessSpecialRiskExperienceBehindBetterResultTreatFiveRelationship" +
      "AnimalImproveHairStayTopReducePerhapsLateWriterPickElseSignifica" +
      "ntChanceHotelGeneralRockRequireAlongFitThemselvesReportCondition" +
      "ReachTruthEffortDecideRateEducationForceGardenDrugLeaderVoiceQui" +
      "teWholeSeemMindFinallySirReturnFreeStoryRespondPushAccordingBrot" +
      "herLearnSonHopeDevelopFeelingReadCarryDiseaseRoadVariousBallCase" +
      "OperationCloseVisitReceiveBuildingValueResearchFullModelJoinSeas" +
      "onKnownDirectorPositionPlayerSportErrorRecordRowDataPaperTheoryS" +
      "paceEveryFormSupportActionOfficialWhoseIdeaHappyHeartBestTeamPro" +
      "jectHitBaseRepresentTownPullBusMapDryMomCatDadRoomSmileFieldImpa" +
      "ctFundLargeDogHugePrepareEnvironmentalProduceHerselfTeachOilSuch" +
      "SituationTieCostIndustrySkinStreetImageItselfPhonePriceWearMostS" +
      "unSoonClearPracticePieceWaitRecentImportantProductLeftWallSeries" +
      "NewsShareMovieKidNorSimplyWifeOntoCatchMyselfFineComputerSongAtt" +
      "entionDrawFilmRepublicanSecurityScoreTestStockPositiveCauseCentu" +
      "ryWindowMemoryExistListenStraightCultureBillionFormerDecisionEne" +
      "rgyMoveSummerWonderRelateAvailableLineLikelyOutsideShotShortCoun" +
      "tryRoleAreaSingleRuleDaughterMarketIndicatePresentLandCampaignMa" +
      "terialPopulationEconomyMedicalHospitalChurchGroundThousandAuthor" +
      "ityInsteadRecentlyFutureWrongInvolveLifeHeightIncreaseRightBankC" +
      "ulturalCertainlyWestExecutiveBoardSeekLongOfficerStatementRestBa" +
      "yDealWorkerResourceThrowForwardPolicyScienceEyesBedItemWeaponFil" +
      "lPlanMilitaryGunHotHeatAddressColdFocusForeignTreatmentBloodUpon" +
      "CourseThirdWatchAffectEarlyStoreThusSoundEverywhereBabyAdministr" +
      "ationMouthPageEnterProbablyPointSeatNaturalRaceFarChallengePassA" +
      "pplyMailUsuallyMixToughClearlyGrowFactorStateLocalGuyEastSaveSou" +
      "thSceneMotherCareerQuicklyCentralFaceIceAboveBeyondPictureNetwor" +
      "kManagementIndividualWomanSizeSpeedBusySeriousOccurAddReadySignC" +
      "ollectionListApproachChargeQualityPressureVoteNotePartRealWebCur" +
      "rentDetermineTrueSadWhateverBreakWorryCupParticularlyAmountAbili" +
      "tyEatRecognizeSitCharacterSomebodyLossDegreeEffectAttackStaffMid" +
      "dleTelevisionWhyLegalCapitalTradeElectionEverybodyDropMajorViewS" +
      "tandardBillEmployeeDiscussionOpportunityAnalysisTenSuggestLawyer" +
      "HusbandSectionBecomeSkillSisterStyleCrimeProgramCompareCapMissBa" +
      "dSortTrainingEasyNearRegionStrategyPurposePerformTechnologyEcono" +
      "micBudgetExampleCheckEnvironmentDoneDarkTermRatherLaughGuessCarL" +
      "owerHangPastSocialForgetHundredRemoveManagerEnjoyExactlyDieFinal" +
      "MaybeHealthFloorChangeAmericanPoorFunEstablishTrialSpringDinnerB" +
      "igThankProtectAvoidImagineTonightStarArmFinishMusicOwnerCryArtPr" +
      "ivateOthersSimplePopularReflectEspeciallySmallLightMessageStepKe" +
      "yPeaceProgressMadeSideGreatFixInterviewManageNationalFishLoseCam" +
      "eraDiscussEqualWeightPerformanceSevenWaterProductionPersonalCell" +
      "PowerEveningColorInsideBarUnitLessAdultWideRangeMentionDeepEdgeS" +
      "trongHardTroubleNecessarySafeCommonFearFamilySeaDreamConferenceR" +
      "eplyPropertyMeetingAlwaysStuffAgencyDeathGrowthSellSoldierActHea" +
      "vyWetBagMarriageDeadSingRiseDecadeWhomFigurePoliceBodyMachineCat" +
      "egoryAheadFrontCareOrderRealityPartnerYardBeatViolenceTotalDefen" +
      "seWriteConsumerCenterGroupThoughtModernTaskCoachReasonAgeFingerS" +
      "pecificConnectionWishResponsePrettyMovementCardLogNumberSumTreeE" +
      "ntireCitizenThroughoutPetSimilarVictimNewspaperThreatClassShakeS" +
      "ourceAccountPainFallRichPossibleAcceptSolidTravelTalkSaidCreateN" +
      "onePlentyPeriodDefineNormalRevealDrinkAuthorServeNameMomentAgent" +
      "DocumentActivityAnywayAfraidTypeActiveTrainInterestingRadioDange" +
      "rGenerationLeafCopyMatchClaimAnyoneSoftwarePartyDeviceCodeLangua" +
      "geLinkHoweverConfirmCommentCityAnywhereSomewhereDebateDriveHighe" +
      "rBeautifulOnlineFanPriorityTraditionalSixUnited").getBytes();

   private static final byte[] BASE64_SYMBOLS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".getBytes();

   private static final byte[] NUMERIC_SYMBOLS = "0123456789+-*/=,.:; ".getBytes();

   private static final byte[] DNA_SYMBOLS = "acgntuACGNTU".getBytes(); // either T or U and N for unknown


   private static final DictEntry[] STATIC_DICTIONARY = new DictEntry[1024];
   private static final int STATIC_DICT_WORDS = createDictionary(DICT_EN_1024, STATIC_DICTIONARY, 1024, 0);

   private final ByteTransform delegate;


   public TextCodec()
   {
      this.delegate = new TextCodec1();
   }


   public TextCodec(Map<String, Object> ctx)
   {
      // Encode the word indexes as varints with a token or with a mask
      final int encodingType = (int) ctx.getOrDefault("textcodec", 1);
      this.delegate = (encodingType == 1) ? new TextCodec1(ctx) : new TextCodec2(ctx);
   }


   // Create dictionary from array of words
   private static int createDictionary(byte[] words, DictEntry[] dict, int maxWords, int startWord)
   {
      int anchor = 0;
      int h = HASH1;
      int nbWords = startWord;

      for (int i=0; ((i<words.length) && (nbWords<maxWords)); i++)
      {
         if (isText(words[i]) == false)
            continue;

         if (isUpperCase(words[i]))
         {
            if (i > anchor)
            {
               dict[nbWords] = new DictEntry(words, anchor, h, nbWords, i-anchor);
               nbWords++;
               anchor = i;
               h = HASH1;
            }

            words[i] ^= 0x20;
         }

         h = h*HASH1 ^ words[i]*HASH2;
      }

      if (nbWords < maxWords)
      {
         dict[nbWords] = new DictEntry(words, anchor, h, nbWords, words.length-anchor);
         nbWords++;
      }

      return nbWords;
   }


   private static boolean isText(byte val)
   {
      return isLowerCase(val) || isUpperCase(val);
   }


   private static boolean isLowerCase(byte val)
   {
      return (val >= 'a') && (val <= 'z');
   }


   private static boolean isUpperCase(byte val)
   {
      return (val >= 'A') && (val <= 'Z');
   }


   public static boolean isDelimiter(byte val)
   {
      return DELIMITER_CHARS[val&0xFF];
   }


   // Analyze the block and return an 8-bit status (see MASK flags constants)
   // The goal is to detect test data amenable to pre-processing.
   public static int computeStats(byte[] block, final int srcIdx, final int srcEnd, int[] freqs0, boolean strict)
   {
      final int[][] freqs = new int[256][256];

      for (int i=0; i<256; i++)
         freqs[i] = new int[256];

      int prv = 0;
      final int count = srcEnd - srcIdx;
      final int srcEnd4 = srcIdx + (count & -4);

      // Unroll loop
      for (int i=srcIdx; i<srcEnd4; i+=4)
      {
         final int cur0 = block[i]   & 0xFF;
         final int cur1 = block[i+1] & 0xFF;
         final int cur2 = block[i+2] & 0xFF;
         final int cur3 = block[i+3] & 0xFF;
         freqs0[cur0]++;
         freqs0[cur1]++;
         freqs0[cur2]++;
         freqs0[cur3]++;
         freqs[prv][cur0]++;
         freqs[cur0][cur1]++;
         freqs[cur1][cur2]++;
         freqs[cur2][cur3]++;
         prv = cur3;
      }

      for (int i=srcEnd4; i<srcEnd; i++)
      {
         final int cur = block[i] & 0xFF;
         freqs0[cur]++;
         freqs[prv][cur]++;
         prv = cur;
      }

      int nbTextChars = freqs0[CR] + freqs0[LF];
      int nbASCII = 0;

      for (int i=0; i<128; i++)
      {
         if (isText((byte) i) == true)
            nbTextChars += freqs0[i];

         nbASCII += freqs0[i];
      }

      // Not text (crude thresholds)
      boolean notText;

      if (strict == true)
         notText = ((nbTextChars < (count>>2)) || (freqs0[0] >= (count/100)) || ((nbASCII/95) < (count/100)));
      else
         notText = ((nbTextChars < (count>>1)) || (freqs0[32] < (count>>5))); 

      if (notText == true)
      {
         int sum = 0;

         for (int i=0; i<12; i++)
            sum += freqs0[DNA_SYMBOLS[i]];

         if (sum == count)
            return MASK_DNA;

         sum = 0;

         for (int i=0; i<20; i++)
            sum += freqs0[NUMERIC_SYMBOLS[i]];

         if (sum >= (count/100)*98)
            return MASK_NUMERIC;

         sum = 0;

         for (int i=0; i<64; i++)
            sum += freqs0[BASE64_SYMBOLS[i]];

         if (sum == count)
            return MASK_BASE64;

         sum = 0;

         for (int i=0; i<256; i++)
         {
            if (freqs0[i] > 0)
               sum++;
         }

         return (sum == 255) ? MASK_BIN : MASK_NOT_TEXT;
      }

      final int nbBinChars = count - nbASCII;

      // Not text (crude threshold)
      if (nbBinChars > (count>>2))
         return MASK_NOT_TEXT;

      int res = 0;

      if (nbBinChars == 0)
         res |= MASK_FULL_ASCII;

      if (nbBinChars <= count-count/10)
      {
         // Check if likely XML/HTML
         // Another crude test: check that the frequencies of < and > are similar
         // and 'high enough'. Also check it is worth to attempt replacing ampersand sequences.
         // Getting this flag wrong results in a very small compression speed degradation.
         final int f1 = freqs0['<'];
         final int f2 = freqs0['>'];
         final int f3 = freqs['&']['a'] + freqs['&']['g'] + freqs['&']['l'] +freqs['&']['q'];
         final int minFreq = Math.max((count-nbBinChars)>>9, 2);

         if ((f1 >= minFreq) && (f2 >= minFreq) && (f3 > 0))
         {
            if (f1 < f2)
            {
               if (f1 >= f2-f2/100)
                  res |= MASK_XML_HTML;
            }
            else if (f2 < f1)
            {
               if (f2 >= f1-f1/100)
                  res |= MASK_XML_HTML;
            }
            else
               res |= MASK_XML_HTML;
         }
      }

      if ((freqs0[CR] != 0) && (freqs0[CR] == freqs0[LF]))
      {
         res |= MASK_CRLF;

         for (int i=0; i<256; i++)
         {
            if ((i != LF) && (freqs[CR][i]) != 0)
            {
               res &= ~MASK_CRLF;
               break;
            }

            if ((i != CR) && (freqs[i][LF]) != 0)
            {
               res &= ~MASK_CRLF;
               break;
            }
         }
      }

      return res;
   }


   public static boolean sameWords(DictEntry e, byte[] src, int anchor, int length)
   {
      final byte[] buf = e.buf;

      // Skip first position (same result)
      for (int i=e.pos+1, j=anchor, l=e.pos+length; i<=l; i++, j++)
      {
         if (buf[i] != src[j])
            return false;
      }

      return true;
   }


   @Override
   public int getMaxEncodedLength(int srcLength)
   {
      return this.delegate.getMaxEncodedLength(srcLength);
   }


   @Override
   public boolean forward(SliceByteArray src, SliceByteArray dst)
   {
      if (src.length == 0)
         return true;

      if (src.array == dst.array)
         return false;

      if (src.length > MAX_BLOCK_SIZE)
         throw new IllegalArgumentException("The max text transform block size is "+MAX_BLOCK_SIZE+", got "+src.length);

      return this.delegate.forward(src, dst);
   }


   @Override
   public boolean inverse(SliceByteArray src, SliceByteArray dst)
   {
      if (src.length == 0)
         return true;

      if (src.array == dst.array)
         return false;

      if (src.length > MAX_BLOCK_SIZE)
         throw new IllegalArgumentException("The max text transform block size is "+MAX_BLOCK_SIZE+", got "+src.length);

      return this.delegate.inverse(src, dst);
   }



   // Encode word indexes using a token
   static class TextCodec1 implements ByteTransform
   {
      private DictEntry[] dictMap;
      private DictEntry[] dictList;
      private final int staticDictSize;
      private final int logHashSize;
      private final int hashMask;
      private boolean isCRLF; // EOL = CR+LF ?
      private int dictSize;
      private Map<String, Object> ctx;


      public TextCodec1()
      {
         this.logHashSize = LOG_HASHES_SIZE;
         this.dictSize = 1<<13;
         this.dictMap = new DictEntry[0];
         this.dictList = new DictEntry[0];
         this.hashMask = (1<<this.logHashSize) - 1;
         this.staticDictSize = STATIC_DICT_WORDS + 2;
      }


      public TextCodec1(Map<String, Object> ctx)
      {
         int log = 13;

         if (ctx.containsKey("blockSize"))
         {
            // Actual block size
            final int blockSize = (Integer) ctx.get("blockSize");

            if (blockSize >= 8)
               log = Math.max(Math.min(Global.log2(blockSize/8), 26), 13);
         }

         boolean extraPerf = (Boolean) ctx.getOrDefault("extra", false);
         log += (extraPerf == true) ? 1 : 0;
         this.logHashSize = log;
         this.dictSize = 1<<13;
         this.dictMap = new DictEntry[0];
         this.dictList = new DictEntry[0];
         this.hashMask = (1<<this.logHashSize) - 1;
         this.staticDictSize = STATIC_DICT_WORDS + 2;
         this.ctx = ctx;
      }


      private void reset(int count)
      {
         // Select an appropriate initial dictionary size
         final int log = (count < 8) ? 13 : Math.max(Math.min(Global.log2(count / 8), 22), 17);
         this.dictSize = 1 << (log - 4);

         // Allocate lazily (only if text input detected)
         if (this.dictMap.length == 0)
         {
            this.dictMap = new DictEntry[1<<this.logHashSize];
         }
         else
         {
            for (int i=0; i<this.dictMap.length; i++)
               this.dictMap[i] = null;
         }

         if (this.dictList.length == 0)
         {
            this.dictList = new DictEntry[this.dictSize];
            System.arraycopy(STATIC_DICTIONARY, 0, this.dictList, 0, Math.min(STATIC_DICTIONARY.length, this.dictSize));

            // Add special entries at end of static dictionary
            this.dictList[STATIC_DICT_WORDS]   = new DictEntry(new byte[] { ESCAPE_TOKEN2 }, 0, 0, STATIC_DICT_WORDS, 1);
            this.dictList[STATIC_DICT_WORDS+1] = new DictEntry(new byte[] { ESCAPE_TOKEN1 }, 0, 0, STATIC_DICT_WORDS+1, 1);
         }

         for (int i=0; i<this.staticDictSize; i++)
         {
            DictEntry e = this.dictList[i];
            this.dictMap[e.hash&this.hashMask] = e;
         }

         // Pre-allocate all dictionary entries
         for (int i=this.staticDictSize; i<this.dictSize; i++)
            this.dictList[i] = new DictEntry(null, -1, 0, i, 0);
      }


      @Override
      public boolean forward(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;

         if (output.length - output.index < this.getMaxEncodedLength(count))
            return false;

         final byte[] src = input.array;
         final byte[] dst = output.array;
         int srcIdx = input.index;
         int dstIdx = output.index;
         final int srcEnd = input.index + count;

         if (this.ctx != null)
         {
            Global.DataType dt = (Global.DataType) this.ctx.getOrDefault("dataType",
               Global.DataType.UNDEFINED);

            if ((dt != Global.DataType.UNDEFINED) && (dt != Global.DataType.TEXT))
               return false;
         }

         int[] freqs0 = new int[256];
         final int mode = computeStats(src, srcIdx, srcEnd, freqs0, true);

         // Not text ?
         if ((mode & MASK_NOT_TEXT) != 0)
         {
            if (this.ctx != null)
            {
               switch (mode)
               {
                  case MASK_NUMERIC:
                    this.ctx.put("dataType", Global.DataType.NUMERIC);
                    break;
                  case MASK_BASE64:
                    this.ctx.put("dataType", Global.DataType.BASE64);
                    break;
                  case MASK_BIN:
                    this.ctx.put("dataType", Global.DataType.BIN);
                    break;
                  case MASK_DNA:
                    this.ctx.put("dataType", Global.DataType.DNA);
                    break;
                  default :
                    break;
               }
            }

            return false;
         }

         if (this.ctx != null)
            this.ctx.put("dataType", Global.DataType.TEXT);

         this.reset(count);
         final int dstEnd = output.index + this.getMaxEncodedLength(count);
         final int dstEnd4 = dstEnd - 4;
         int emitAnchor = input.index; // never less than input.index
         int words = this.staticDictSize;

         // DOS encoded end of line (CR+LF) ?
         this.isCRLF = (mode & MASK_CRLF) != 0;
         dst[dstIdx++] = (byte) mode;
         boolean res = true;

         while ((srcIdx < srcEnd) && (src[srcIdx] == ' '))
         {
            dst[dstIdx++] = ' ';
            srcIdx++;
            emitAnchor++;
         }

         int delimAnchor = isText(src[srcIdx]) ? srcIdx-1 : srcIdx; // previous delimiter

         while (srcIdx < srcEnd)
         {
            final byte cur = src[srcIdx];

            if (isText(cur))
            {
               srcIdx++;
               continue;
            }

            if ((srcIdx > delimAnchor+2) && isDelimiter(cur)) // At least 2 letters
            {
               final int length = srcIdx - delimAnchor - 1;

               if (length <= MAX_WORD_LENGTH)
               {
                  // Compute hashes
                  // h1 -> hash of word chars
                  // h2 -> hash of word chars with first char case flipped
                  final byte val = src[delimAnchor+1];
                  int h1 = HASH1*HASH1 ^ val*HASH2;
                  int h2 = HASH1*HASH1 ^ (val^0x20)*HASH2;

                  for (int i=delimAnchor+2; i<srcIdx; i++)
                  {
                     final int h = src[i]*HASH2;
                     h1 = h1*HASH1 ^ h;
                     h2 = h2*HASH1 ^ h;
                  }

                  // Check word in dictionary
                  DictEntry e = null;
                  DictEntry e1 = this.dictMap[h1&this.hashMask];

                  // Check for hash collisions
                  if ((e1 != null) && (e1.hash == h1) && ((e1.data>>>24) == length))
                     e = e1;

                  if (e == null)
                  {
                     DictEntry e2 = this.dictMap[h2&this.hashMask];

                     if ((e2 != null) && (e2.hash == h2) && ((e2.data>>>24) == length))
                        e = e2;
                  }

                  if (e != null)
                  {
                     if (sameWords(e, src, delimAnchor+2, length-1) == false)
                        e = null;
                  }

                  if (e == null)
                  {
                     // Word not found in the dictionary or hash collision.
                     // Replace entry if not in static dictionary
                     if (((length > 3) || ((length == 3) && (words < THRESHOLD2))) && (e1 == null))
                     {
                        e = this.dictList[words];

                        if ((e.data&MASK_LENGTH) >= this.staticDictSize)
                        {
                           // Reuse old entry
                           this.dictMap[e.hash&this.hashMask] = null;
                           e.buf = src;
                           e.pos = delimAnchor + 1;
                           e.hash = h1;
                           e.data = (length<<24) | words;
                        }

                        this.dictMap[h1&this.hashMask] = e;
                        words++;

                        // Dictionary full ? Expand or reset index to end of static dictionary
                        if (words >= this.dictSize)
                        {
                           if (this.expandDictionary() == false)
                              words = this.staticDictSize;
                        }
                     }
                  }
                  else
                  {
                     // Word found in the dictionary
                     // Skip space if only delimiter between 2 word references
                     if ((emitAnchor != delimAnchor) || (src[delimAnchor] != ' '))
                     {
                        final int dIdx = this.emitSymbols(src, emitAnchor, dst, dstIdx, delimAnchor+1, dstEnd);

                        if (dIdx < 0)
                        {
                           res = false;
                           break;
                        }

                        dstIdx = dIdx;
                     }

                     if (dstIdx >= dstEnd4)
                     {
                        res = false;
                        break;
                     }

                     dst[dstIdx++] = (e == e1) ? ESCAPE_TOKEN1 : ESCAPE_TOKEN2;
                     dstIdx = emitWordIndex(dst, dstIdx, e.data&MASK_LENGTH);
                     emitAnchor = delimAnchor + 1 + (e.data>>>24);
                  }
               }
            }

            // Reset delimiter position
            delimAnchor = srcIdx;
            srcIdx++;
         }

         if (res == true)
         {
            // Emit last symbols
            final int dIdx = this.emitSymbols(src, emitAnchor, dst, dstIdx, srcEnd, dstEnd);

            if (dIdx < 0)
               res = false;
            else
               dstIdx = dIdx;

            res &= (srcIdx == srcEnd);
         }

         output.index = dstIdx;
         input.index = srcIdx;
         return res;
      }


      private boolean expandDictionary()
      {
         if (this.dictSize >= MAX_DICT_SIZE)
            return false;

         DictEntry[] newDict = new DictEntry[this.dictSize*2];
         System.arraycopy(this.dictList, 0, newDict, 0, this.dictSize);

         for (int i=this.dictSize; i<this.dictSize*2; i++)
            newDict[i] = new DictEntry(null, -1, 0, i, 0);

         this.dictList = newDict;
         this.dictSize <<= 1;
         return true;
      }


      private int emitSymbols(byte[] src, final int srcIdx, byte[] dst, int dstIdx, final int srcEnd, final int dstEnd)
      {
         for (int i=srcIdx; i<srcEnd; i++)
         {
            if (dstIdx >= dstEnd)
               return -1;

            final byte cur = src[i];

            switch (cur)
            {
               case ESCAPE_TOKEN1 :
               case ESCAPE_TOKEN2 :
                  // Emit special word
                  dst[dstIdx++] = ESCAPE_TOKEN1;
                  final int idx = (cur == ESCAPE_TOKEN1) ? this.staticDictSize-1 : this.staticDictSize-2;
                  int lenIdx = 2;

                  if (idx >= THRESHOLD2)
                     lenIdx = 3;
                  else if (idx < THRESHOLD1)
                     lenIdx = 1;

                  if (dstIdx+lenIdx >= dstEnd)
                     return -1;

                  dstIdx = emitWordIndex(dst, dstIdx, idx);
                  break;

               case CR :
                  if (this.isCRLF == false)
                     dst[dstIdx++] = cur;

                  break;

               default:
                  dst[dstIdx++] = cur;
            }
         }

         return dstIdx;
      }


      private static int emitWordIndex(byte[] dst, int dstIdx, int val)
      {
         // Emit word index (varint 5 bits + 7 bits + 7 bits)
         if (val >= THRESHOLD1)
         {
            if (val >= THRESHOLD2)
               dst[dstIdx++] = (byte) (0xE0|(val>>14));

            dst[dstIdx]   = (byte) (0x80|(val>>7));
            dst[dstIdx+1] = (byte) (0x7F&val);
            return dstIdx + 2;
         }

         dst[dstIdx] = (byte) val;
         return dstIdx + 1;
      }


      @Override
      public boolean inverse(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;
         int srcIdx = input.index;
         int dstIdx = output.index;
         final byte[] src = input.array;
         final byte[] dst = output.array;

         this.reset(output.length);
         final int srcEnd = input.index + count;
         final int dstEnd = dst.length - 1;

         int delimAnchor = isText(src[srcIdx]) ? srcIdx-1 : srcIdx; // previous delimiter
         int words = this.staticDictSize;
         boolean wordRun = false;
         final boolean _isCRLF = (src[srcIdx++] & MASK_CRLF) != 0;
         this.isCRLF = _isCRLF;

         while ((srcIdx < srcEnd) && (dstIdx < dstEnd))
         {
            byte cur = src[srcIdx];

            if (isText(cur))
            {
               dst[dstIdx] = cur;
               srcIdx++;
               dstIdx++;
               continue;
            }

            if ((srcIdx > delimAnchor+3) && isDelimiter(cur))
            {
               final int length = srcIdx - delimAnchor - 1; // length > 2

               if (length <= MAX_WORD_LENGTH)
               {
                  int h1 = HASH1;

                  for (int i=delimAnchor+1; i<srcIdx; i++)
                     h1 = h1*HASH1 ^ src[i]*HASH2;

                  // Lookup word in dictionary
                  DictEntry e = null;
                  DictEntry e1 = this.dictMap[h1&this.hashMask];

                  // Check for hash collisions
                  if ((e1 != null) && (e1.hash == h1) && ((e1.data>>>24) == length))
                  {
                     if (sameWords(e1, src, delimAnchor+2, length-1) == true)
                        e = e1;
                  }

                  if (e == null)
                  {
                     // Word not found in the dictionary or hash collision.
                     // Replace entry if not in static dictionary
                     if (((length > 3) || (words < THRESHOLD2)) && (e1 == null))
                     {
                        e = this.dictList[words];

                        if ((e.data&MASK_LENGTH) >= this.staticDictSize)
                        {
                           // Reuse old entry
                           this.dictMap[e.hash&this.hashMask] = null;
                           e.buf = src;
                           e.pos = delimAnchor + 1;
                           e.hash = h1;
                           e.data = (length<<24) | words;
                        }

                        this.dictMap[h1&this.hashMask] = e;
                        words++;

                        // Dictionary full ? Expand or reset index to end of static dictionary
                        if (words >= this.dictSize)
                        {
                           if (this.expandDictionary() == false)
                              words = this.staticDictSize;
                        }
                     }
                  }
               }
            }

            srcIdx++;

            if ((cur == ESCAPE_TOKEN1) || (cur == ESCAPE_TOKEN2))
            {
               // Word in dictionary
               // Read word index (varint 5 bits + 7 bits + 7 bits)
               int idx = src[srcIdx++] & 0xFF;

               if ((idx&0x80) != 0)
               {
                  idx &= 0x7F;
                  int idx2 = src[srcIdx++];

                  if ((idx2&0x80) != 0)
                  {
                     idx = ((idx&0x1F)<<7) | (idx2&0x7F);
                     idx2 = src[srcIdx++] & 0x7F;
                  }

                  idx = (idx<<7) | idx2;

                  if (idx >= this.dictSize)
                     break;
               }

               final DictEntry e = this.dictList[idx];
               final int length = e.data >>> 24;
               final byte[] buf = e.buf;

               // Sanity check
               if ((e.pos < 0) || (dstIdx+length >= dstEnd))
                  break;

               // Add space if only delimiter between 2 words (not an escaped delimiter)
               if ((wordRun == true) && (length > 1))
                  dst[dstIdx++] = ' ';

               // Emit word
               if (cur != ESCAPE_TOKEN2)
               {
                  dst[dstIdx++] = (byte) buf[e.pos];
               }
               else
               {
                  // Flip case of first character
                  dst[dstIdx++] = (byte) (buf[e.pos]^0x20);
               }

               if (length > 1)
               {
                  for (int n=e.pos+1, l=e.pos+length; n<l; n++, dstIdx++)
                     dst[dstIdx] = buf[n];

                  // Regular word entry
                  wordRun = true;
                  delimAnchor = srcIdx;
               }
               else
               {
                  // Escape entry
                  wordRun = false;
                  delimAnchor = srcIdx-1;
               }
            }
            else
            {
               wordRun = false;
               delimAnchor = srcIdx-1;

               if ((_isCRLF == true) && (cur == LF))
                  dst[dstIdx++] = CR;

               dst[dstIdx++] = cur;
            }
         }

         output.index = dstIdx;
         input.index = srcIdx;
         return srcIdx == srcEnd;
      }


      @Override
      public int getMaxEncodedLength(int srcLength)
      {
         // Limit to 1 x srcLength and let the caller deal with
         // a failure when the output is too small
         return srcLength;
      }
   }


   // Encode word indexes using a mask (0x80)
   static class TextCodec2 implements ByteTransform
   {
      private DictEntry[] dictMap;
      private DictEntry[] dictList;
      private final int staticDictSize;
      private final int logHashSize;
      private final int hashMask;
      private boolean isCRLF; // EOL = CR+LF ?
      private int dictSize;
      private Map<String, Object> ctx;


      public TextCodec2()
      {
         this.logHashSize = LOG_HASHES_SIZE;
         this.dictSize = 1<<13;
         this.dictMap = new DictEntry[0];
         this.dictList = new DictEntry[0];
         this.hashMask = (1<<this.logHashSize) - 1;
         this.staticDictSize = STATIC_DICT_WORDS;
      }


      public TextCodec2(Map<String, Object> ctx)
      {
         int log = 13;

         if (ctx.containsKey("blockSize"))
         {
            // Actual block size
            final int blockSize = (Integer) ctx.get("blockSize");

            if (blockSize >= 32)
               log = Math.max(Math.min(Global.log2(blockSize/32), 24), 13);
         }

         boolean extraPerf = (Boolean) ctx.getOrDefault("extra", false);
         log += (extraPerf == true) ? 1 : 0;
         this.logHashSize = log;
         this.dictSize = 1<<13;
         this.dictMap = new DictEntry[0];
         this.dictList = new DictEntry[0];
         this.hashMask = (1<<this.logHashSize) - 1;
         this.staticDictSize = STATIC_DICT_WORDS;
         this.ctx = ctx;
      }


      private void reset(int count)
      {
         // Select an appropriate initial dictionary size
         final int log = (count < 8) ? 13 : Math.max(Math.min(Global.log2(count / 8), 22), 17);
         this.dictSize = 1 << (log - 4);

         // Allocate lazily (only if text input detected)
         if (this.dictMap.length == 0)
         {
            this.dictMap = new DictEntry[1<<this.logHashSize];
         }
         else
         {
            for (int i=0; i<this.dictMap.length; i++)
               this.dictMap[i] = null;
         }

         if (this.dictList.length == 0)
         {
            this.dictList = new DictEntry[this.dictSize];
            System.arraycopy(STATIC_DICTIONARY, 0, this.dictList, 0, Math.min(STATIC_DICTIONARY.length, this.dictSize));
         }

         // Update map
         for (int i=0; i<this.staticDictSize; i++)
         {
            DictEntry e = this.dictList[i];
            this.dictMap[e.hash&this.hashMask] = e;
         }

         // Pre-allocate all dictionary entries
         for (int i=this.staticDictSize; i<this.dictSize; i++)
            this.dictList[i] = new DictEntry(null, -1, 0, i, 0);
      }


      @Override
      public boolean forward(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;

         if (output.length - output.index < this.getMaxEncodedLength(count))
            return false;

         final byte[] src = input.array;
         final byte[] dst = output.array;
         int srcIdx = input.index;
         int dstIdx = output.index;
         final int srcEnd = input.index + count;

         if (this.ctx != null)
         {
            Global.DataType dt = (Global.DataType) this.ctx.getOrDefault("dataType",
               Global.DataType.UNDEFINED);

            if ((dt != Global.DataType.UNDEFINED) && (dt != Global.DataType.TEXT))
               return false;
         }

         int[] freqs0 = new int[256];
         final int mode = computeStats(src, srcIdx, srcEnd, freqs0, false);

         // Not text ?
         if ((mode & MASK_NOT_TEXT) != 0)
         {
            if (this.ctx != null)
            {
               switch (mode)
               {
                  case MASK_NUMERIC:
                    this.ctx.put("dataType", Global.DataType.NUMERIC);
                    break;
                  case MASK_BASE64:
                    this.ctx.put("dataType", Global.DataType.BASE64);
                    break;
                  case MASK_BIN:
                    this.ctx.put("dataType", Global.DataType.BIN);
                    break;
                  case MASK_DNA:
                    this.ctx.put("dataType", Global.DataType.DNA);
                    break;
                  default :
                    break;
               }
            }

            return false;
         }

         if (this.ctx != null)
            this.ctx.put("dataType", Global.DataType.TEXT);

         this.reset(count);
         final int dstEnd = output.index + this.getMaxEncodedLength(count);
         final int dstEnd3 = dstEnd - 3;
         int emitAnchor = input.index; // never less than input.index
         int words = this.staticDictSize;

         // DOS encoded end of line (CR+LF) ?
         this.isCRLF = (mode & MASK_CRLF) != 0;
         dst[dstIdx++] = (byte) mode;
         boolean res = true;

         while ((srcIdx < srcEnd) && (src[srcIdx] == ' '))
         {
            dst[dstIdx++] = ' ';
            srcIdx++;
            emitAnchor++;
         }

         int delimAnchor = isText(src[srcIdx]) ? srcIdx-1 : srcIdx; // previous delimiter

         while (srcIdx < srcEnd)
         {
            final byte cur = src[srcIdx];

            if (isText(cur))
            {
               srcIdx++;
               continue;
            }

            if ((srcIdx > delimAnchor+2) && isDelimiter(cur)) // At least 2 letters
            {
               final int length = srcIdx - delimAnchor - 1;

               if (length <= MAX_WORD_LENGTH)
               {
                  // Compute hashes
                  // h1 -> hash of word chars
                  // h2 -> hash of word chars with first char case flipped
                  final byte val = src[delimAnchor+1];
                  int h1 = HASH1*HASH1 ^ val*HASH2;
                  int h2 = HASH1*HASH1 ^ (val^0x20)*HASH2;

                  for (int i=delimAnchor+2; i<srcIdx; i++)
                  {
                     final int h = src[i] * HASH2;
                     h1 = h1*HASH1 ^ h;
                     h2 = h2*HASH1 ^ h;
                  }

                  // Check word in dictionary
                  DictEntry e = null;
                  DictEntry e1 = this.dictMap[h1&this.hashMask];

                  // Check for hash collisions
                  if ((e1 != null) && (e1.hash == h1) && ((e1.data>>>24) == length))
                     e = e1;

                  if (e == null)
                  {
                     DictEntry e2 = this.dictMap[h2&this.hashMask];

                     if ((e2 != null) && (e2.hash == h2) && ((e2.data>>>24) == length))
                        e = e2;
                  }

                  if (e != null)
                  {
                     if (sameWords(e, src, delimAnchor+2, length-1) == false)
                        e = null;
                  }

                  if (e == null)
                  {
                     // Word not found in the dictionary or hash collision.
                     // Replace entry if not in static dictionary
                     if (((length > 3) || ((length == 3) && (words < THRESHOLD2))) && (e1 == null))
                     {
                        e = this.dictList[words];

                        if ((e.data&MASK_LENGTH) >= this.staticDictSize)
                        {
                           // Reuse old entry
                           this.dictMap[e.hash&this.hashMask] = null;
                           e.buf = src;
                           e.pos = delimAnchor + 1;
                           e.hash = h1;
                           e.data = (length<<24) | words;
                        }

                        this.dictMap[h1&this.hashMask] = e;
                        words++;

                        // Dictionary full ? Expand or reset index to end of static dictionary
                        if (words >= this.dictSize)
                        {
                           if (this.expandDictionary() == false)
                              words = this.staticDictSize;
                        }
                     }
                  }
                  else
                  {
                     // Word found in the dictionary
                     // Skip space if only delimiter between 2 word references
                     if ((emitAnchor != delimAnchor) || (src[delimAnchor] != ' '))
                     {
                        final int dIdx = this.emitSymbols(src, emitAnchor, dst, dstIdx, delimAnchor+1, dstEnd);

                        if (dIdx < 0)
                        {
                           res = false;
                           break;
                        }

                        dstIdx = dIdx;
                     }

                     if (dstIdx >= dstEnd3)
                     {
                        res = false;
                        break;
                     }

                     dstIdx = emitWordIndex(dst, dstIdx, e.data&MASK_LENGTH, ((e == e1) ? 0 : 32));
                     emitAnchor = delimAnchor + 1 + (e.data>>>24);
                  }
               }
            }

            // Reset delimiter position
            delimAnchor = srcIdx;
            srcIdx++;
         }

         // Emit last symbols
         if (res == true)
         {
            final int dIdx = this.emitSymbols(src, emitAnchor, dst, dstIdx, srcEnd, dstEnd);

            if (dIdx < 0)
               res = false;
            else
               dstIdx = dIdx;
         }

         output.index = dstIdx;
         input.index = srcIdx;
         res &= (srcIdx == srcEnd);
         return res;
      }


      private boolean expandDictionary()
      {
         if (this.dictSize >= MAX_DICT_SIZE)
            return false;

         DictEntry[] newDict = new DictEntry[this.dictSize*2];
         System.arraycopy(this.dictList, 0, newDict, 0, this.dictSize);

         for (int i=this.dictSize; i<this.dictSize*2; i++)
            newDict[i] = new DictEntry(null, -1, 0, i, 0);

         this.dictList = newDict;
         this.dictSize <<= 1;
         return true;
      }


      private int emitSymbols(byte[] src, final int srcIdx, byte[] dst, int dstIdx, final int srcEnd, final int dstEnd)
      {
         if (dstIdx+2*(srcEnd-srcIdx) < dstEnd)
         {
            for (int i=srcIdx; i<srcEnd; i++)
            {
               final byte cur = src[i];

               switch (cur)
               {
                  case ESCAPE_TOKEN1:
                     dst[dstIdx++] = ESCAPE_TOKEN1;
                     dst[dstIdx++] = ESCAPE_TOKEN1;
                     break;

                  case CR :
                     if (this.isCRLF == false)
                        dst[dstIdx++] = cur;

                     break;

                  default:
                     if ((cur & 0x80) != 0)
                        dst[dstIdx++] = ESCAPE_TOKEN1;

                     dst[dstIdx++] = cur;
               }
            }
         }
         else
         {
            for (int i=srcIdx; i<srcEnd; i++)
            {
               final byte cur = src[i];

               switch (cur)
               {
                  case ESCAPE_TOKEN1:
                     if (dstIdx >= dstEnd-1)
                        return -1;

                     dst[dstIdx++] = ESCAPE_TOKEN1;
                     dst[dstIdx++] = ESCAPE_TOKEN1;
                     break;

                  case CR :
                     if (this.isCRLF == false)
                     {
                        if (dstIdx >= dstEnd)
                           return -1;

                        dst[dstIdx++] = cur;
                     }

                     break;

                  default:
                     if ((cur & 0x80) != 0)
                     {
                        if (dstIdx >= dstEnd)
                           return -1;

                        dst[dstIdx++] = ESCAPE_TOKEN1;
                     }

                     if (dstIdx >= dstEnd)
                        return -1;

                     dst[dstIdx++] = cur;
               }
            }
         }

         return dstIdx;
      }


      private static int emitWordIndex(byte[] dst, int dstIdx, int val, int mask)
      {
         // Emit word index (varint 5 bits + 7 bits + 7 bits)
         // 1st byte: 0x80 => word idx, 0x40 => more bytes, 0x20 => toggle case 1st symbol
         // 2nd byte: 0x80 => 1 more byte
         if (val >= THRESHOLD3)
         {
            if (val >= THRESHOLD4)
            {
               // 5 + 7 + 7 => 2^19
               dst[dstIdx]   = (byte) (0xC0|mask|((val>>14)&0x1F));
               dst[dstIdx+1] = (byte) (0x80|(val>>7));
               dst[dstIdx+2] = (byte) (val&0x7F);
               return dstIdx + 3;
            }

            // 5 + 7 => 2^12 = 32*128
            dst[dstIdx]   = (byte) (0xC0|mask|(val>>7));
            dst[dstIdx+1] = (byte) (val&0x7F);
            return dstIdx + 2;
         }

         // 5 => 32
         dst[dstIdx] = (byte) (0x80|mask|val);
         return dstIdx + 1;
      }


      @Override
      public boolean inverse(SliceByteArray input, SliceByteArray output)
      {
         final int count = input.length;
         int srcIdx = input.index;
         int dstIdx = output.index;
         final byte[] src = input.array;
         final byte[] dst = output.array;

         this.reset(output.length);
         final int srcEnd = input.index + count;
         final int dstEnd = dst.length - 1;
         int delimAnchor = isText(src[srcIdx]) ? srcIdx-1 : srcIdx; // previous delimiter
         int words = this.staticDictSize;
         boolean wordRun = false;
         final boolean _isCRLF = (src[srcIdx++] & MASK_CRLF) != 0;
         this.isCRLF = _isCRLF;

         while ((srcIdx < srcEnd) && (dstIdx < dstEnd))
         {
            byte cur = src[srcIdx];

            if (isText(cur))
            {
               dst[dstIdx] = cur;
               srcIdx++;
               dstIdx++;
               continue;
            }

            if ((srcIdx > delimAnchor+3) && isDelimiter(cur))
            {
               final int length = srcIdx - delimAnchor - 1; // length > 2

               if (length <= MAX_WORD_LENGTH)
               {
                  int h1 = HASH1;

                  for (int i=delimAnchor+1; i<srcIdx; i++)
                     h1 = h1*HASH1 ^ src[i]*HASH2;

                  // Lookup word in dictionary
                  DictEntry e = null;
                  DictEntry e1 = this.dictMap[h1&this.hashMask];

                  // Check for hash collisions
                  if ((e1 != null) && (e1.hash == h1) && ((e1.data>>>24) == length))
                  {
                     if (sameWords(e1, src, delimAnchor+2, length-1) == true)
                        e = e1;
                  }

                  if (e == null)
                  {
                     // Word not found in the dictionary or hash collision.
                     // Replace entry if not in static dictionary
                     if (((length > 3) || (words < THRESHOLD2)) && (e1 == null))
                     {
                        e = this.dictList[words];

                        if ((e.data&MASK_LENGTH) >= this.staticDictSize)
                        {
                           // Reuse old entry
                           this.dictMap[e.hash&this.hashMask] = null;
                           e.buf = src;
                           e.pos = delimAnchor + 1;
                           e.hash = h1;
                           e.data = (length<<24) | words;
                        }

                        this.dictMap[h1&this.hashMask] = e;
                        words++;

                        // Dictionary full ? Expand or reset index to end of static dictionary
                        if (words >= this.dictSize)
                        {
                           if (this.expandDictionary() == false)
                              words = this.staticDictSize;
                        }
                     }
                  }
               }
            }

            srcIdx++;

            if ((cur & 0x80) != 0)
            {
               // Word in dictionary
               // Read word index (varint 5 bits + 7 bits + 7 bits)
               int idx = cur & 0x1F;

               if ((cur&0x40) != 0)
               {
                  int idx2 = src[srcIdx++];

                  if ((idx2&0x80) != 0)
                  {
                     idx = (idx<<7) | (idx2&0x7F);
                     idx2 = src[srcIdx++] & 0x7F;
                  }

                  idx = (idx<<7) | idx2;

                  if (idx >= this.dictSize)
                     break;
               }

               final DictEntry e = this.dictList[idx];
               final int length = e.data >>> 24;
               final byte[] buf = e.buf;

               // Sanity check
               if ((e.pos < 0) || (dstIdx+length >= dstEnd))
                  break;

               // Add space if only delimiter between 2 words (not an escaped delimiter)
               if ((wordRun == true) && (length > 1))
                  dst[dstIdx++] = ' ';

               // Flip case of first character
               dst[dstIdx++] = (byte) (buf[e.pos]^(cur & 0x20));

               if (length > 1)
               {
                  // Emit word
                  for (int n=e.pos+1, l=e.pos+length; n<l; n++, dstIdx++)
                     dst[dstIdx] = buf[n];

                  // Regular word entry
                  wordRun = true;
                  delimAnchor = srcIdx;
               }
               else
               {
                  // Escape entry
                  wordRun = false;
                  delimAnchor = srcIdx-1;
               }
            }
            else
            {
               if (cur == ESCAPE_TOKEN1)
               {
                  dst[dstIdx++] = src[srcIdx++];
               }
               else
               {
                  if ((_isCRLF == true) && (cur == LF))
                     dst[dstIdx++] = CR;

                  dst[dstIdx++] = cur;
               }

               wordRun = false;
               delimAnchor = srcIdx-1;
            }
         }

         output.index = dstIdx;
         input.index = srcIdx;
         return srcIdx == srcEnd;
      }


      @Override
      public int getMaxEncodedLength(int srcLength)
      {
         // Limit to 1 x srcLength and let the caller deal with
         // a failure when the output is too small
         return srcLength;
      }
   }


   public static class DictEntry
   {
      int hash; // full word hash
      int pos;  // position in text
      int data; // packed word length (8 MSB) + index in dictionary (24 LSB)
      byte[] buf;  // text data


      DictEntry(byte[] buf, int pos, int hash, int idx, int length)
      {
         this.buf = buf;
         this.pos = pos;
         this.hash = hash;
         this.data = (length << 24) | idx;
      }

      @Override
      public String toString()
      {
         final int length = this.data >>> 24;
         StringBuilder sb = new StringBuilder(length);

         for (int i=0; i<length; i++)
            sb.append((char) this.buf[this.pos+i]);

         return sb.toString();
      }
   }
}