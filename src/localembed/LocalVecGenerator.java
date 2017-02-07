/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package localembed;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.deeplearning4j.models.embeddings.loader.WordVectorSerializer;
import org.deeplearning4j.models.paragraphvectors.ParagraphVectors;
import org.deeplearning4j.models.word2vec.Word2Vec;
import org.deeplearning4j.models.word2vec.wordstore.inmemory.InMemoryLookupCache;
import org.deeplearning4j.text.sentenceiterator.SentenceIterator;
import org.deeplearning4j.text.tokenization.tokenizer.preprocessor.CommonPreprocessor;
import org.deeplearning4j.text.tokenization.tokenizerfactory.DefaultTokenizerFactory;
import org.deeplearning4j.text.tokenization.tokenizerfactory.TokenizerFactory;
import org.deeplearning4j.text.sentenceiterator.BasicLineIterator;
import org.deeplearning4j.text.documentiterator.LabelsSource;
import org.deeplearning4j.text.sentenceiterator.LuceneSentenceIterator;
import org.deeplearning4j.text.sentenceiterator.SentencePreProcessor;
import retrievability.RetrievabilityFinder;
import retrievability.RetrievabilityScore;

/**
 *
 * @author Debasis
 */

public class LocalVecGenerator {

    Properties prop;
    
    // The docfile is a single tab separated file... each line in the file
    // representing a new document...
    // <DOCID> \t <TEXT>    
    int minwordfreq;
    String stopFile;
    int numDimensions;
    Analyzer analyzer;
    String indexDir;
    
    IndexWriter writer;  //in-mem index of the local wordvecs
    
    static public String FIELD_CORE_WORDS = "corewords";
    static public String FIELD_LOCAL_WVEC = "localwvec";
    
    public LocalVecGenerator(Properties prop, Analyzer analyzer) throws Exception {
        this.prop = prop;
        minwordfreq = Integer.parseInt(prop.getProperty("minwordfreq", "2"));
        stopFile = prop.getProperty("stopfile");
        numDimensions = Integer.parseInt(prop.getProperty("vec.numdimensions", "200"));
        this.analyzer = analyzer;
        indexDir = prop.getProperty("index");
        
        // in-mem index for the local word vectors
        RAMDirectory localWordVecDir = new RAMDirectory();
        IndexWriterConfig iwcfg = new IndexWriterConfig(new WebDocAnalyzer("stopfile"));
        iwcfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        IndexWriter writer = new IndexWriter(localWordVecDir, iwcfg);
        
    }

    // Read sentences from Lucene index
    Word2Vec learnLocalWordVecs(List<RetrievabilityScore> docIds) throws Exception {
        String contentFieldName = prop.getProperty("content.field.name", "content");
        SentenceIterator iter = new LuceneDocIterator(
                new File(indexDir),
                analyzer,
                contentFieldName,
                docIds
        );
        InMemoryLookupCache cache = new InMemoryLookupCache();

        TokenizerFactory t = new DefaultTokenizerFactory();
        t.setTokenPreProcessor(new CommonPreprocessor());
        
        Word2Vec vec = new Word2Vec.Builder()
                .minWordFrequency(minwordfreq)
                .iterations(3)
                .epochs(5)
                .layerSize(numDimensions)
                .learningRate(0.025)
                .windowSize(5)
                .iterate(iter)
                .vocabCache(cache)
                .tokenizerFactory(t)
                .sampling(0.1f)
                .workers(4)
                .build();
        vec.fit();
        return vec;
    }
    
    /*
    Expects an input file of the following format:
        word and NNs separated by spaces 
    */
    void processLine(String line) throws Exception {
        String[] tokens = line.split("\\s+");
        List<String> coreWords = Arrays.asList(tokens);

        RetrievabilityFinder rfinder = new RetrievabilityFinder(prop, coreWords);
        List<RetrievabilityScore> rscores = rfinder.getTopRetrievableDocs();

        Word2Vec localvec = learnLocalWordVecs(rscores);
        saveLocalWordVecModel(line, localvec);        
    }

    void saveLocalWordVecModel(String coreWords, Word2Vec vec) throws Exception {
        System.out.println("Saving local word vectors in-mem...");
        
        // Write the bytes to output stream
        ByteArrayOutputStream bos = new ByteArrayOutputStream();        
        WordVectorSerializer.writeWordVectors(vec, bos);
        
        Document d = new Document();
        // searchable on core words
        d.add(new Field(FIELD_CORE_WORDS, coreWords, Field.Store.YES, Field.Index.ANALYZED));
        d.add(new StoredField(FIELD_LOCAL_WVEC, bos.toByteArray()));
        
        writer.addDocument(d);        
    }
    
    public void processAll() throws Exception {
        
        System.out.println("Learning local word embeddings on a subset of docs");
        String fileName = prop.getProperty("localwvec.corewordlist");
        FileReader fr = new FileReader(fileName);
        BufferedReader br = new BufferedReader(fr);
        String line;
                
        while ((line = br.readLine()) != null) {
            processLine(line);
        }
        
        br.close();
        fr.close();
    }
        
    public static void main(String[] args) {
        if (args.length == 0) {
            args = new String[1];
            System.out.println("Usage: java Doc2VecGenerator <prop-file>");
            args[0] = "init.properties";
        }

        try {
            Properties prop = new Properties();
            prop.load(new FileReader(args[0]));
            WebDocAnalyzer analyzer = new WebDocAnalyzer(prop.getProperty("stopfile"));
            LocalVecGenerator doc2vecGen = new LocalVecGenerator(prop, analyzer);
            doc2vecGen.processAll();
        }
        catch (Exception ex) { ex.printStackTrace(); }
    }
}