package edu.stanford.nlp.pipeline;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.stanford.nlp.ling.CoreAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.MultiTokenTag;
import edu.stanford.nlp.ling.tokensregex.EnvLookup;
import edu.stanford.nlp.time.SUTime;
import edu.stanford.nlp.time.SUTimeSimpleParser;
import edu.stanford.nlp.util.*;
import edu.stanford.nlp.util.logging.Redwood;


/**
 * An annotator which removes all XML tags (as identified by the
 * tokenizer) and possibly selectively keeps the text between them.
 * Can also add sentence-ending markers depending on the XML tag.
 * Note that the removal of tags is done by a finite state tokenizer.
 * Thus, this works for simple, typical XML, or equally for similar
 * SGML or XML tags, but will not work on arbitrarily complicated XML.
 *
 * @author John Bauer
 * @author Angel Chang
 */
public class CleanXmlAnnotator implements Annotator {

  /**
   * A regular expression telling us where to look for tokens
   * we care about
   */
  private final Pattern xmlTagMatcher;

  public static final String DEFAULT_XML_TAGS = ".*";

  /** A logger for this class */
  private static Redwood.RedwoodChannels log = Redwood.channels(CleanXmlAnnotator.class);

  /**
   * This regular expression tells us which tags end a sentence...
   * for example, {@code <p>} would be a great candidate.
   */
  private final Pattern sentenceEndingTagMatcher;

  public static final String DEFAULT_SENTENCE_ENDERS = "";

  /**
   * This tells us what tags denote single sentences (tokens inside should not be sentence split on)
   */
  private Pattern singleSentenceTagMatcher; // = null;

  public static final String DEFAULT_SINGLE_SENTENCE_TAGS = null;

  /**
   * This tells us which XML tags wrap document date.
   */
  private final Pattern dateTagMatcher;

  public static final String DEFAULT_DATE_TAGS = "datetime|date";

  /**
   * This tells us which XML tags wrap document id
   */
  private Pattern docIdTagMatcher;

  public static final String DEFAULT_DOCID_TAGS = "docid";

  /**
   * This tells us which XML tags wrap document type
   */
  private Pattern docTypeTagMatcher;

  public static final String DEFAULT_DOCTYPE_TAGS = "doctype";

  /**
   * This tells us when an utterance turn starts
   * (used in dcoref)
   */
  private Pattern utteranceTurnTagMatcher; // = null;

  public static final String DEFAULT_UTTERANCE_TURN_TAGS = "turn";

  /**
   * This tells us what the speaker tag is
   * (used in dcoref)
   */
  private Pattern speakerTagMatcher; // = null;

  public static final String DEFAULT_SPEAKER_TAGS = "speaker";

  /**
   * A map of document level annotation keys (i.e., docid) along with a pattern
   *  indicating the tag to match, and the attribute to match.
   */
  private final CollectionValuedMap<Class, Pair<Pattern,Pattern>> docAnnotationPatterns = new CollectionValuedMap<>();
  public static final String DEFAULT_DOC_ANNOTATIONS_PATTERNS = "docID=doc[id],doctype=doc[type],docsourcetype=doctype[source]";

  /**
   * A map of token level annotation keys (i.e., link, speaker) along with a pattern
   *  indicating the tag/attribute to match (tokens that belong to the text enclosed in the specified tag will be annotated).
   */
  private final CollectionValuedMap<Class, Pair<Pattern,Pattern>> tokenAnnotationPatterns = new CollectionValuedMap<>();
  public static final String DEFAULT_TOKEN_ANNOTATIONS_PATTERNS = null;

  /**
   * This tells us what the section tag is
   */
  private Pattern sectionTagMatcher; // = null;
  public static final String DEFAULT_SECTION_TAGS = null;

  /**
   * This tells us what tokens will be discarded by ssplit
   */
  private Pattern ssplitDiscardTokensMatcher; // = null;

  /**
   * A map of section level annotation keys (i.e. docid) along with a pattern
   *  indicating the tag to match, and the attribute to match.
   */
  private final CollectionValuedMap<Class, Pair<Pattern,Pattern>> sectionAnnotationPatterns = new CollectionValuedMap<>();
  public static final String DEFAULT_SECTION_ANNOTATIONS_PATTERNS = null;
  
  /**
   * This setting allows handling of "flawed XML", which may be valid SGML.  For example,
   * a lot of the news articles we parse go: <br>
   *  &lt;text&gt; <br>
   *  &lt;turn&gt; <br>
   *  &lt;turn&gt; <br>
   *  &lt;turn&gt; <br>
   *  &lt;/text&gt; <br>
   * ... i.e., no closing &lt;/turn&gt; tags.
   */
  private final boolean allowFlawedXml;

  public static final boolean DEFAULT_ALLOW_FLAWS = true;

  public CleanXmlAnnotator() {
    this(DEFAULT_XML_TAGS, DEFAULT_SENTENCE_ENDERS, DEFAULT_DATE_TAGS, DEFAULT_ALLOW_FLAWS);
  }

  public CleanXmlAnnotator(Properties properties) {
    String xmlTagsToRemove =
        properties.getProperty("clean.xmltags",
            CleanXmlAnnotator.DEFAULT_XML_TAGS);
    String sentenceEndingTags =
        properties.getProperty("clean.sentenceendingtags",
            CleanXmlAnnotator.DEFAULT_SENTENCE_ENDERS);
    String singleSentenceTags =
        properties.getProperty("clean.singlesentencetags",
            CleanXmlAnnotator.DEFAULT_SINGLE_SENTENCE_TAGS);
    String allowFlawedString = properties.getProperty("clean.allowflawedxml");
    boolean allowFlawed = CleanXmlAnnotator.DEFAULT_ALLOW_FLAWS;
    if (allowFlawedString != null)
      allowFlawed = Boolean.valueOf(allowFlawedString);
    this.allowFlawedXml = allowFlawed;
    String dateTags =
        properties.getProperty("clean.datetags",
            CleanXmlAnnotator.DEFAULT_DATE_TAGS);
    String docIdTags =
        properties.getProperty("clean.docIdtags",
            CleanXmlAnnotator.DEFAULT_DOCID_TAGS);
    String docTypeTags =
        properties.getProperty("clean.docTypetags",
            CleanXmlAnnotator.DEFAULT_DOCTYPE_TAGS);
    String utteranceTurnTags =
        properties.getProperty("clean.turntags",
            CleanXmlAnnotator.DEFAULT_UTTERANCE_TURN_TAGS);
    String speakerTags =
        properties.getProperty("clean.speakertags",
            CleanXmlAnnotator.DEFAULT_SPEAKER_TAGS);
    String docAnnotations =
        properties.getProperty("clean.docAnnotations",
            CleanXmlAnnotator.DEFAULT_DOC_ANNOTATIONS_PATTERNS);
    String tokenAnnotations =
        properties.getProperty("clean.tokenAnnotations",
            CleanXmlAnnotator.DEFAULT_TOKEN_ANNOTATIONS_PATTERNS);
    String sectionTags =
        properties.getProperty("clean.sectiontags",
            CleanXmlAnnotator.DEFAULT_SECTION_TAGS);
    String sectionAnnotations =
        properties.getProperty("clean.sectionAnnotations",
            CleanXmlAnnotator.DEFAULT_SECTION_ANNOTATIONS_PATTERNS);

    String ssplitDiscardTokens =
        properties.getProperty("clean.ssplitDiscardTokens");

    if (xmlTagsToRemove != null) {
      xmlTagMatcher = toCaseInsensitivePattern(xmlTagsToRemove);
      if (StringUtils.isNullOrEmpty(sentenceEndingTags)) {
        sentenceEndingTagMatcher = null;
      } else {
        sentenceEndingTagMatcher = toCaseInsensitivePattern(sentenceEndingTags);
      }
    } else {
      xmlTagMatcher = null;
      sentenceEndingTagMatcher = null;
    }

    dateTagMatcher = toCaseInsensitivePattern(dateTags);

    setSingleSentenceTagMatcher(singleSentenceTags);
    setDocIdTagMatcher(docIdTags);
    setDocTypeTagMatcher(docTypeTags);
    setDiscourseTags(utteranceTurnTags, speakerTags);
    setDocAnnotationPatterns(docAnnotations);
    setTokenAnnotationPatterns(tokenAnnotations);
    setSectionTagMatcher(sectionTags);
    setSectionAnnotationPatterns(sectionAnnotations);
    setSsplitDiscardTokensMatcher(ssplitDiscardTokens);

  }

  public CleanXmlAnnotator(String xmlTagsToRemove,
                           String sentenceEndingTags,
                           String dateTags,
                           boolean allowFlawedXml) {
    this.allowFlawedXml = allowFlawedXml;
    if (xmlTagsToRemove != null) {
      xmlTagMatcher = toCaseInsensitivePattern(xmlTagsToRemove);
      if (StringUtils.isNullOrEmpty(sentenceEndingTags)) {
        sentenceEndingTagMatcher = null;
      } else {
        sentenceEndingTagMatcher = toCaseInsensitivePattern(sentenceEndingTags);
      }
    } else {
      xmlTagMatcher = null;
      sentenceEndingTagMatcher = null;
    }

    dateTagMatcher = toCaseInsensitivePattern(dateTags);
  }


  private static Pattern toCaseInsensitivePattern(String tags) {
    if (tags != null) {
      return Pattern.compile(tags, Pattern.CASE_INSENSITIVE);
    } else {
      return null;
    }
  }

  public void setSsplitDiscardTokensMatcher(String tags) {
    ssplitDiscardTokensMatcher = toCaseInsensitivePattern(tags);
  }

  public void setSingleSentenceTagMatcher(String tags) {
    singleSentenceTagMatcher = toCaseInsensitivePattern(tags);
  }

  public void setDocIdTagMatcher(String docIdTags) {
    docIdTagMatcher = toCaseInsensitivePattern(docIdTags);
  }

  public void setDocTypeTagMatcher(String docTypeTags) {
    docTypeTagMatcher = toCaseInsensitivePattern(docTypeTags);
  }

  public void setSectionTagMatcher(String sectionTags) {
    sectionTagMatcher = toCaseInsensitivePattern(sectionTags);
  }

  public void setDiscourseTags(String utteranceTurnTags, String speakerTags) {
    utteranceTurnTagMatcher = toCaseInsensitivePattern(utteranceTurnTags);
    speakerTagMatcher = toCaseInsensitivePattern(speakerTags);
  }

  public void setDocAnnotationPatterns(String conf) {
    docAnnotationPatterns.clear();
    // Patterns can only be tag attributes
    addAnnotationPatterns(docAnnotationPatterns, conf, true);
  }

  public void setTokenAnnotationPatterns(String conf) {
    tokenAnnotationPatterns.clear();
    // Patterns can only be tag attributes
    addAnnotationPatterns(tokenAnnotationPatterns, conf, true);
  }

  public void setSectionAnnotationPatterns(String conf) {
    sectionAnnotationPatterns.clear();
    addAnnotationPatterns(sectionAnnotationPatterns, conf, false);
  }

  private static final Pattern TAG_ATTR_PATTERN = Pattern.compile("(.*)\\[(.*)\\]");

  private static void addAnnotationPatterns(CollectionValuedMap<Class, Pair<Pattern,Pattern>> annotationPatterns, String conf, boolean attrOnly) {
    String[] annoPatternStrings = conf == null ? StringUtils.EMPTY_STRING_ARRAY : conf.trim().split("\\s*,\\s*");
    for (String annoPatternString:annoPatternStrings) {
      String[] annoPattern = annoPatternString.split("\\s*=\\s*", 2);
      if (annoPattern.length != 2) {
        throw new IllegalArgumentException("Invalid annotation to tag pattern: " + annoPatternString);
      }
      String annoKeyString = annoPattern[0];
      String pattern = annoPattern[1];
      Class annoKey = EnvLookup.lookupAnnotationKeyWithClassname(null, annoKeyString);
      if (annoKey == null) {
        throw new IllegalArgumentException("Cannot resolve annotation key " + annoKeyString);
      }
      Matcher m = TAG_ATTR_PATTERN.matcher(pattern);
      if (m.matches()) {
        Pattern tagPattern = toCaseInsensitivePattern(m.group(1));
        Pattern attrPattern = toCaseInsensitivePattern(m.group(2));
        annotationPatterns.add(annoKey, Pair.makePair(tagPattern, attrPattern));
      } else {
        if (attrOnly) {
          // attribute is require
          throw new IllegalArgumentException("Invalid tag pattern: " + pattern + " for annotation key " + annoKeyString);
        } else {
          Pattern tagPattern = toCaseInsensitivePattern(pattern);
          annotationPatterns.add(annoKey, Pair.makePair(tagPattern, (Pattern) null));
        }
      }
    }
  }

  @Override
  public void annotate(Annotation annotation) {
    if (annotation.containsKey(CoreAnnotations.TokensAnnotation.class)) {
      List<CoreLabel> tokens = annotation.get(CoreAnnotations.TokensAnnotation.class);
      List<CoreLabel> newTokens = process(annotation, tokens);
      // We assume that if someone is using this annotator, they don't
      // want the old tokens any more and get rid of them
      annotation.set(CoreAnnotations.TokensAnnotation.class, newTokens);
    }
  }

  public List<CoreLabel> process(List<CoreLabel> tokens) {
    return process(null, tokens);
  }

  private static String tokensToString(Annotation annotation, List<CoreLabel> tokens) {
    if (tokens.isEmpty()) return "";
    // Try to get original text back?
    String annotationText = (annotation != null)? annotation.get(CoreAnnotations.TextAnnotation.class) : null;
    if (annotationText != null) {
      CoreLabel firstToken = tokens.get(0);
      CoreLabel lastToken = tokens.get(tokens.size() - 1);
      int firstCharOffset = firstToken.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class);
      int lastCharOffset = lastToken.get(CoreAnnotations.CharacterOffsetEndAnnotation.class);
      return annotationText.substring(firstCharOffset, lastCharOffset);
    } else {
      return StringUtils.joinWords(tokens, " ");
    }
  }

  // Annotates CoreMap with information from xml tag

  /**
   * Updates a CoreMap with attributes (or text context) from a tag.
   *
   * @param annotation - Main document level annotation (from which the original text can be extracted)
   * @param cm - CoreMap to annotate
   * @param tag - tag to process
   * @param annotationPatterns - list of annotation patterns to match
   * @param savedTokens - tokens for annotations that are text context of a tag
   * @param toAnnotate - what keys to annotate
   * @return The set of annotations found
   */
  private static Set<Class> annotateWithTag(Annotation annotation,
                                     CoreMap cm,
                                     XMLUtils.XMLTag tag,
                                     CollectionValuedMap<Class, Pair<Pattern,Pattern>> annotationPatterns,
                                     Map<Class, List<CoreLabel>> savedTokens,
                                     Collection<Class> toAnnotate,
                                     Map<Class, Stack<Pair<String, String>>> savedTokenAnnotations) {
    Set<Class> foundAnnotations = new HashSet<>();
    if (annotationPatterns == null) { return foundAnnotations; }
    if (toAnnotate == null) {
      toAnnotate = annotationPatterns.keySet();
    }
    for (Class key:toAnnotate) {
      for (Pair<Pattern,Pattern> pattern: annotationPatterns.get(key)) {
        Pattern tagPattern = pattern.first;
        Pattern attrPattern = pattern.second;
        if (tagPattern.matcher(tag.name).matches()) {
          boolean matched = false;
          if (attrPattern != null) {
            if (tag.attributes != null) {
              for (Map.Entry<String,String> entry:tag.attributes.entrySet()) {
                if (attrPattern.matcher(entry.getKey()).matches()) {
                  if (savedTokenAnnotations != null) {
                    Stack<Pair<String, String>> stack = savedTokenAnnotations.get(key);
                    if (stack == null) {
                      savedTokenAnnotations.put(key, stack = new Stack<>());
                    }
                    stack.push(Pair.makePair(tag.name, entry.getValue()));
                  }
                  cm.set(key, entry.getValue());
                  foundAnnotations.add(key);
                  matched = true;
                  break;
                }
              }
            }
            if (savedTokenAnnotations != null) {
              if (tag.isEndTag) {
                // tag ended - clear this annotation
                Stack<Pair<String, String>> stack = savedTokenAnnotations.get(key);
                if (stack != null && !stack.isEmpty()) {
                  Pair<String,String> p = stack.peek();
                  if (p.first.equalsIgnoreCase(tag.name)) {
                    stack.pop();
                    if (!stack.isEmpty()) {
                      cm.set(key, stack.peek().second);
                    } else {
                      cm.remove(key);
                    }
                  }
                }
              }
            }
          } else if (savedTokens != null) {
            if (tag.isEndTag && !tag.isSingleTag) {
              // End tag - annotate using saved tokens
              List<CoreLabel> saved = savedTokens.remove(key);
              if (saved != null && saved.size() > 0) {
                cm.set(key, tokensToString(annotation, saved));
                foundAnnotations.add(key);
                matched = true;
              }
            } else {
              // Start tag
              savedTokens.put(key, new ArrayList<>());
            }
          }
          if (matched) break;
        }
      }
    }
    return foundAnnotations;
  }

  public List<CoreLabel> process(Annotation annotation, List<CoreLabel> tokens) {
    // As we are processing, this stack keeps track of which tags we
    // are currently inside
    Stack<String> enclosingTags = new Stack<>();
    // here we keep track of the current enclosingTags
    // this lets multiple tokens reuse the same tag stack
    List<String> currentTagSet = null;
    // How many matching tags we've seen
    int matchDepth = 0;
    // stores the filtered tags as we go
    List<CoreLabel> newTokens = new ArrayList<>();

    // we use this to store the before & after annotations if the
    // tokens were tokenized for "invertible"
    StringBuilder removedText = new StringBuilder();
    // we keep track of this so we can look at the last tag after
    // we're outside the loop

    // Keeps track of what we still need to doc level annotations
    // we still need to look for
    Set<Class> toAnnotate = new HashSet<>(docAnnotationPatterns.keySet());

    int utteranceIndex = 0;
    boolean inUtterance = false;
    boolean inSpeakerTag = false;
    String currentSpeaker = null;
    List<CoreLabel> speakerTokens = new ArrayList<>();
    List<CoreLabel> docDateTokens = new ArrayList<>();
    List<CoreLabel> docTypeTokens = new ArrayList<>();
    List<CoreLabel> docIdTokens = new ArrayList<>();

    // add an annotation for storing info about sections of a document
    // a section could be a discussion forum post, a blog entry, etc...
    annotation.set(CoreAnnotations.SectionsAnnotation.class, new ArrayList<>());

    // Local variables for additional per token annotations
    CoreMap tokenAnnotations = (tokenAnnotationPatterns != null && !tokenAnnotationPatterns.isEmpty())? new ArrayCoreMap():null;
    Map<Class, Stack<Pair<String, String>>> savedTokenAnnotations = new ArrayMap<>();

    // Local variable for annotating sections
    XMLUtils.XMLTag sectionStartTag = null;
    CoreLabel sectionStartToken = null;
    CoreMap sectionAnnotations = null;
    Map<Class, List<CoreLabel>> savedTokensForSection = new HashMap<>();

    boolean markSingleSentence = false;
    for (CoreLabel token : tokens) {
      String word = token.word().trim();
      XMLUtils.XMLTag tag = XMLUtils.parseTag(word);

      // If it's not a tag, we do manipulations such as unescaping
      if (tag == null) {
        // TODO: put this into the lexer instead of here
        token.setWord(XMLUtils.unescapeStringForXML(token.word()));
        // TODO: was there another annotation that also represents the word?
        if (matchDepth > 0 ||
            xmlTagMatcher == null ||
            xmlTagMatcher.matcher("").matches()) {
          newTokens.add(token);
          if (inUtterance) {
            token.set(CoreAnnotations.UtteranceAnnotation.class, utteranceIndex);
            if (currentSpeaker != null) token.set(CoreAnnotations.SpeakerAnnotation.class, currentSpeaker);
          }
          if (markSingleSentence) {
            token.set(CoreAnnotations.ForcedSentenceUntilEndAnnotation.class, true);
            markSingleSentence = false;
          }
          if (tokenAnnotations != null) {
            ChunkAnnotationUtils.copyUnsetAnnotations(tokenAnnotations, token);
          }
        }
        // if we removed any text, and the tokens are "invertible" and
        // therefore keep track of their before/after text, append
        // what we removed to the appropriate tokens
        if (removedText.length() > 0) {
          boolean added = false;
          String before = token.get(CoreAnnotations.BeforeAnnotation.class);
          if (before != null) {
            token.set(CoreAnnotations.BeforeAnnotation.class, removedText + before);
            added = true;
          }
          if (added && newTokens.size() > 1) {
            CoreLabel previous = newTokens.get(newTokens.size() - 2);
            String after = previous.get(CoreAnnotations.AfterAnnotation.class);
            if (after != null)
              previous.set(CoreAnnotations.AfterAnnotation.class, after + removedText);
            else
              previous.set(CoreAnnotations.AfterAnnotation.class, removedText.toString());
          }
          removedText = new StringBuilder();
        }
        if (currentTagSet == null) {
          // We wrap the list in an unmodifiable list because we reuse
          // the same list object many times.  We don't want to
          // let someone modify one list and screw up all the others.
          currentTagSet =
            Collections.unmodifiableList(new ArrayList<>(enclosingTags));
        }
        token.set(CoreAnnotations.XmlContextAnnotation.class, currentTagSet);

        // is this token part of the doc date sequence?
        if (dateTagMatcher != null &&
            ! currentTagSet.isEmpty() &&
            dateTagMatcher.matcher(currentTagSet.get(currentTagSet.size() - 1)).matches()) {
          docDateTokens.add(token);
        }

        if (docIdTagMatcher != null &&
                ! currentTagSet.isEmpty() &&
                docIdTagMatcher.matcher(currentTagSet.get(currentTagSet.size() - 1)).matches()) {
          docIdTokens.add(token);
        }

        if (docTypeTagMatcher != null &&
                ! currentTagSet.isEmpty() &&
                docTypeTagMatcher.matcher(currentTagSet.get(currentTagSet.size() - 1)).matches()) {
          docTypeTokens.add(token);
        }

        if (inSpeakerTag) {
          speakerTokens.add(token);
        }

        if (sectionStartTag != null) {
          boolean okay = true;
          if (ssplitDiscardTokensMatcher != null) {
            okay = !ssplitDiscardTokensMatcher.matcher(token.word()).matches();
          }
          if (okay) {
            if (sectionStartToken == null) {
              sectionStartToken = token;
            }
            // Add tokens to saved section tokens
            for (List<CoreLabel> saved:savedTokensForSection.values()) {
              saved.add(token);
            }
          }
        }

        continue;
      }

      // At this point, we know we have a tag

      // we are removing a token and its associated text...
      // keep track of that
      String currentRemoval = token.get(CoreAnnotations.BeforeAnnotation.class);
      if (currentRemoval != null)
        removedText.append(currentRemoval);
      currentRemoval = token.get(CoreAnnotations.OriginalTextAnnotation.class);
      if (currentRemoval != null)
        removedText.append(currentRemoval);
      if (token == tokens.get(tokens.size() - 1)) {
        currentRemoval = token.get(CoreAnnotations.AfterAnnotation.class);
        if (currentRemoval != null)
          removedText.append(currentRemoval);
      }

      // Process tag

      // Check if we want to annotate anything using the tags's attributes
      if (!toAnnotate.isEmpty() && tag.attributes != null) {
        Set<Class> foundAnnotations = annotateWithTag(annotation, annotation, tag, docAnnotationPatterns, null, toAnnotate, null);
        toAnnotate.removeAll(foundAnnotations);
      }

      // Check if the tag matches a section
      if (sectionTagMatcher != null && sectionTagMatcher.matcher(tag.name).matches()) {
        if (tag.isEndTag) {
          annotateWithTag(annotation, sectionAnnotations, tag, sectionAnnotationPatterns, savedTokensForSection, null, null);
          if (sectionStartToken != null) {
            sectionStartToken.set(CoreAnnotations.SectionStartAnnotation.class, sectionAnnotations);
          }
          // Mark previous token as forcing sentence and section end
          if ( ! newTokens.isEmpty()) {
            CoreLabel previous = newTokens.get(newTokens.size() - 1);
            previous.set(CoreAnnotations.ForcedSentenceEndAnnotation.class, true);
            previous.set(CoreAnnotations.SectionEndAnnotation.class, sectionStartTag.name);
          }
          // create a CoreMap to store info about this section
          String foundAuthor = sectionAnnotations.get(CoreAnnotations.AuthorAnnotation.class);
          CoreMap currSectionCoreMap = new Annotation();
          // set character offset of beginning of section
          currSectionCoreMap.set(CoreAnnotations.CharacterOffsetBeginAnnotation.class,
              sectionStartToken.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class));
          // set character offset of end of section
          currSectionCoreMap.set(CoreAnnotations.CharacterOffsetEndAnnotation.class,
              token.get(CoreAnnotations.CharacterOffsetEndAnnotation.class));
          // set author of section
          currSectionCoreMap.set(CoreAnnotations.AuthorAnnotation.class, foundAuthor);
          // set up empty sentences list
          currSectionCoreMap.set(CoreAnnotations.SentencesAnnotation.class, new ArrayList<>());
          // set doc date for post
          String dateString = sectionAnnotations.get(CoreAnnotations.SectionDateAnnotation.class);
          try {
            SUTime.Temporal potentialDate = SUTimeSimpleParser.parse(dateString);
            currSectionCoreMap.set(CoreAnnotations.SectionDateAnnotation.class,
                potentialDate.toString());
          } catch (Exception e) {
            log.error("failed to parse datetime for post!");
          }
          // add this to the list of sections
          annotation.get(CoreAnnotations.SectionsAnnotation.class).add(currSectionCoreMap);
          // finish processing section
          savedTokensForSection.clear();
          sectionStartTag = null;
          sectionStartToken = null;
          sectionAnnotations = null;
        } else if (!tag.isSingleTag) {
          // Prepare to mark first token with section information
          sectionStartTag = tag;
          sectionAnnotations = new ArrayCoreMap();
          sectionAnnotations.set(CoreAnnotations.SectionAnnotation.class, sectionStartTag.name);
        }
      }
      if (sectionStartTag != null) {
        // store away annotations for section
        annotateWithTag(annotation, sectionAnnotations, tag, sectionAnnotationPatterns, savedTokensForSection, null, null);
      }
      if (tokenAnnotations != null) {
        annotateWithTag(annotation, tokenAnnotations, tag, tokenAnnotationPatterns, null, null, savedTokenAnnotations);
      }

      // If the tag matches the sentence ending tags, and we have some
      // existing words, mark that word as being somewhere we want
      // to end the sentence.
      if (sentenceEndingTagMatcher != null &&
          sentenceEndingTagMatcher.matcher(tag.name).matches() && ! newTokens.isEmpty()) {
        CoreLabel previous = newTokens.get(newTokens.size() - 1);
        previous.set(CoreAnnotations.ForcedSentenceEndAnnotation.class, true);
      }

      if (utteranceTurnTagMatcher != null && utteranceTurnTagMatcher.matcher(tag.name).matches()) {
        if ( ! newTokens.isEmpty()) {
          // Utterance turn is also sentence ending
          CoreLabel previous = newTokens.get(newTokens.size() - 1);
          previous.set(CoreAnnotations.ForcedSentenceEndAnnotation.class, true);
        }
        inUtterance = !(tag.isEndTag || tag.isSingleTag);
        if (inUtterance) {
          utteranceIndex++;
        }
        if (!inUtterance) {
          currentSpeaker = null;
        }
      }

      if (speakerTagMatcher != null && speakerTagMatcher.matcher(tag.name).matches()) {
        if ( ! newTokens.isEmpty()) {
          // Speaker is not really part of sentence
          CoreLabel previous = newTokens.get(newTokens.size() - 1);
          previous.set(CoreAnnotations.ForcedSentenceEndAnnotation.class, true);
        }
        inSpeakerTag = !(tag.isEndTag || tag.isSingleTag);
        if (tag.isEndTag) {
          currentSpeaker = tokensToString(annotation, speakerTokens);
          MultiTokenTag.Tag mentionTag = new MultiTokenTag.Tag(currentSpeaker, "Speaker", speakerTokens.size());
          int i = 0;
          for (CoreLabel t:speakerTokens) {
            t.set(CoreAnnotations.SpeakerAnnotation.class, currentSpeaker);
            t.set(CoreAnnotations.MentionTokenAnnotation.class, new MultiTokenTag(mentionTag, i));
            i++;
          }
        } else {
          currentSpeaker = null;
        }
        speakerTokens.clear();
      }

      if (singleSentenceTagMatcher != null && singleSentenceTagMatcher.matcher(tag.name).matches()) {
        if (tag.isEndTag) {
          // Mark previous token as forcing sentence end
          if ( ! newTokens.isEmpty()) {
            CoreLabel previous = newTokens.get(newTokens.size() - 1);
            previous.set(CoreAnnotations.ForcedSentenceEndAnnotation.class, true);
          }
          markSingleSentence = false;
        } else if (!tag.isSingleTag) {
          // Enforce rest of the tokens to be single token until ForceSentenceEnd is seen
          markSingleSentence = true;
        }
      }

      if (xmlTagMatcher == null)
        continue;

      if (tag.isSingleTag) {
        continue;
      }
      // at this point, we can't reuse the "currentTagSet" vector
      // any more, since the current tag set has changed
      currentTagSet = null;
      if (tag.isEndTag) {
        while (true) {
          if (enclosingTags.isEmpty()) {
            throw new IllegalArgumentException("Got a close tag " + tag.name +
                                               " which does not match" +
                                               " any open tag");
          }
          String lastTag = enclosingTags.pop();
          if (xmlTagMatcher.matcher(lastTag).matches()){
            --matchDepth;
          }
          if (lastTag.equals(tag.name))
            break;
          if (!allowFlawedXml)
            throw new IllegalArgumentException("Mismatched tags... " +
                                               tag.name + " closed a " +
                                               lastTag + " tag.");
        }
        if (matchDepth < 0) {
          // this should be impossible, since we already assert that
          // the tags match up correctly
          throw new AssertionError("Programming error?  We think there " +
                                   "have been more close tags than open tags");
        }
      } else {
        // open tag, since all other cases are exhausted
        enclosingTags.push(tag.name);
        if (xmlTagMatcher.matcher(tag.name).matches())
          matchDepth++;
      }
    }

    if ( ! enclosingTags.isEmpty() && ! allowFlawedXml) {
      throw new IllegalArgumentException("Unclosed tags, starting with " +
                                         enclosingTags.pop());
    }

    // If we ended with a string of xml tokens, that text needs to be
    // appended to the "AfterAnnotation" of one of the tokens...
    // Note that we clear removedText when we see a real token, so
    // if removedText is not empty, that must be because we just
    // dropped an xml tag.  Therefore we ignore that old After
    // annotation, since that text was already absorbed in the Before
    // annotation of the xml tag we threw away
    if ( ! newTokens.isEmpty() && removedText.length() > 0) {
      CoreLabel lastToken = newTokens.get(newTokens.size() - 1);
      // sometimes AfterAnnotation seems to be null even when we are
      // collecting before & after annotations, but OriginalTextAnnotation
      // is only non-null if we are invertible.  Hopefully.
      if (lastToken.get(CoreAnnotations.OriginalTextAnnotation.class) != null) {
        lastToken.set(CoreAnnotations.AfterAnnotation.class, removedText.toString());
      }
    }

    // Populate docid, docdate, doctype
    if (annotation != null) {
      if (!docIdTokens.isEmpty()) {
        String str = tokensToString(annotation, docIdTokens).trim();
        annotation.set(CoreAnnotations.DocIDAnnotation.class, str);
      }
      if (!docDateTokens.isEmpty()) {
        String str = tokensToString(annotation, docDateTokens).trim();
        annotation.set(CoreAnnotations.DocDateAnnotation.class, str);
      }
      if (!docTypeTokens.isEmpty()) {
        String str = tokensToString(annotation, docTypeTokens).trim();
        annotation.set(CoreAnnotations.DocTypeAnnotation.class, str);
      }
    }

    return newTokens;
  }

  @Override
  public Set<Class<? extends CoreAnnotation>> requires() {
    return Collections.singleton(CoreAnnotations.TokensAnnotation.class);
  }

  @Override
  public Set<Class<? extends CoreAnnotation>> requirementsSatisfied() {
    return Collections.emptySet();
  }

}
