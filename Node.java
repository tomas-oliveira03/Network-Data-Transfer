import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class Node {
    private String ipNode;
    private boolean killNode = false;
    private String pathToFiles;
    private String defragmentMessages;
    private Socket socketTCP;
    private DatagramSocket socketUDP;
    private BufferedReader bufferedFromTracker; // Ler informação enviada pelo servidor
    private BufferedWriter bufferedToTracker; // Ler informação enviada para o servidor
    private Map<String,Map<Integer,byte[]>> allNodeFiles;

    private ReentrantLock l = new ReentrantLock();

    // Para UDP

    private Map<Integer, byte[]> outputBlocks = new HashMap<>();
    private Map<String, Integer> totalSize = new HashMap<>();
    private Map<String, Integer> fragmentoAtual = new HashMap<>();
    private List<String> hasStarted = new ArrayList<>();
    private Map<String, Integer> n_sequencia_esperado = new HashMap<>();
    private String fileName;
    private boolean hasDownloadStarted = false;
    private Map<String, String> ipToSendAKCS = new HashMap<>();

    private List<String> filesDownloaded = new ArrayList<>();


    public Node(String ip, Socket socketTCP, String pathToFiles, DatagramSocket socketUDP) throws IOException{
        this.ipNode = ip;
        this.defragmentMessages = "";
        this.pathToFiles = pathToFiles;
        this.socketUDP = socketUDP;
        this.allNodeFiles = new HashMap<>();
        this.socketTCP = socketTCP;
        this.bufferedToTracker = new BufferedWriter(new OutputStreamWriter(socketTCP.getOutputStream())); // Enviar 
        this.bufferedFromTracker = new BufferedReader(new InputStreamReader(socketTCP.getInputStream())); // Receber
        sendInfoToFS_Tracker(getFilesInfo());
        keepAlive();
    }




    ////////////////////////////////////////////////////
    ////////////////////////////////////////////////////
    //COMUNICAÇÃO TCP
    ////////////////////////////////////////////////////
    ////////////////////////////////////////////////////



    // Vai a cada ficheiro e guarda-o numa hashmap e cria uma mensagem para ser enviada ao FS_Tracker indicando
    // que blocos é que cada ficheiro possui
    public String getFilesInfo(){
        String payload = "";
        File infoFile = new File(this.pathToFiles);
        File[] allFiles = infoFile.listFiles();

        for (File file : allFiles){
            String fileName = file.getName().substring(0, file.getName().lastIndexOf('.'));
            
            Map<Integer, byte[]> fragmentFile = new HashMap<>();

            Path path = file.toPath();
            byte[] fileInBytes = null;

            try {
                fileInBytes = Files.readAllBytes(path);
            } catch (IOException e) {
                e.printStackTrace();
            }  



            int maxPacketSize = 1017;
            int totalBytes = fileInBytes.length;
            int totalFragments = (int)Math.ceil((double)totalBytes/maxPacketSize);
            payload += fileName + ":1-"+totalFragments+";";
            for (int i = 1; i <= totalFragments; i++){
                int start = (i - 1) * maxPacketSize;
                int end = i * maxPacketSize;
                
                if (end > totalBytes) {
                    end = totalBytes;
                }
                
                fragmentFile.put(i, Arrays.copyOfRange(fileInBytes, start, end));
            }
            allNodeFiles.put(fileName, fragmentFile);
        }
        return payload;                
    }


    // Envio de informação para o FS_Tracker com fragmentação de pacotes, se necessário
    public void sendInfoToFS_Tracker(String payload) throws IOException{
        int maxPayload = 40;
        int payloadSize = payload.length();
        
        if (payloadSize<=maxPayload) {
            // Mensagem não fragmentada
            String finalMessage = this.ipNode + "|" + 1 + "|" + payload;
            // System.out.print("Payload Sent to Tracker: " + finalMessage + "\n\n");  // COLOCAR ATIVO PARA DEMONSTRAR
            bufferedToTracker.write(finalMessage);
            bufferedToTracker.newLine();
            bufferedToTracker.flush();
        }
        else{
            int totalFragments = (int)Math.ceil((double)payloadSize/maxPayload);

            for (int i = 1; i <= totalFragments; i++){
                int start = (i * maxPayload) - maxPayload;
                int end = i * maxPayload;
                String message = "";
                if (end > payloadSize) {
                    end = payloadSize;
                    // Mensagem com fragmentação (É a última)
                    message = this.ipNode + "|" + 3 + "|" + payload.substring(start, end);
                }
                else{
                    // Mensagem com fragmentação (não é a última)
                    message = this.ipNode + "|" + 2 + "|" + payload.substring(start, end);           
                }
                // System.out.println("Payload Sent to Tracker: " + message);  // COLOCAR ATIVO PARA DEMONSTRAR
                bufferedToTracker.write(message);
                bufferedToTracker.newLine();
                bufferedToTracker.flush();
            }
      
        }
    }


    // Responsável por verificar se o nodo nunca perde a conexão com o FS_Tracker
    private Timer timer;
    public void keepAlive(){
        new Thread(() -> {
            timer = new Timer();

            TimerTask task = new TimerTask() {
                
                @Override
                public void run() {
                    try {
                        if (!killNode){
                            // Keep Alive
                            bufferedToTracker.write(ipNode + "|0");
                            bufferedToTracker.newLine();
                            bufferedToTracker.flush();  
                        }
                        
                    } catch (IOException e) {
                        System.out.println("Socket closed");
                        System.exit(0);
                    }
                    
                }
            };

            timer.scheduleAtFixedRate(task, 2000, 2000);
        }).start();
    }


    // Trata de enviar determinadas mensagens com vários significados e funcionalidades ao FS_Tracker
    public void sendMessageToTracker() throws IOException {
        new Thread(() -> {

            Scanner scanner = new Scanner(System.in);
            while(socketTCP.isConnected()  && !killNode) {
                String messageToSend = scanner.nextLine();

                if (messageToSend.equals("d")){
                    try{
                        bufferedToTracker.write(messageToSend);
                        bufferedToTracker.newLine();
                        bufferedToTracker.flush();
                        disconnectNode();
                    }
                    catch (IOException e) {
                        System.out.println(e.getMessage());
                    }
                }
                else if(messageToSend.equals("i")){
                    try{
                        bufferedToTracker.write(messageToSend);
                        bufferedToTracker.newLine();
                        bufferedToTracker.flush();
                    }
                    catch (IOException e) {
                        System.out.println(e.getMessage());
                    }
                }
                else if(messageToSend.startsWith("GET ")){
                    try{
                        fileName = messageToSend.substring(4);
                        bufferedToTracker.write(messageToSend);
                        bufferedToTracker.newLine();
                        bufferedToTracker.flush();
                    }
                    catch (IOException e) {
                        System.out.println(e.getMessage());
                    }
                }
                else{
                    System.out.println("Invalid input!");
                }
            }
            scanner.close();
        }).start();
    }


    // Função responsável por ouvir sempre se existe infomação a ser enviada para esta porta
    public void listenMessageFromTracker() {
        new Thread(() -> {
            String msgFromChat;

            while (socketTCP.isConnected() && !killNode) {
                try {
                    msgFromChat = bufferedFromTracker.readLine();
                    defragmentationFromFSTracker(msgFromChat);
                } catch (IOException e) {
                    if(!killNode){
                        System.out.println("ListenMessageFromTracker ERRO: " + e.getMessage());
                    }
                }
            }
        }).start();
    }


    // Responsável por defragmentar informações enviadas pelo FS_Tracker
    public void defragmentationFromFSTracker(String message){
        if (message.startsWith("File")){
            System.out.println(message);
            return;
        }
        int aux = 0;
        String fragment = "";
        String payload = "";
        for(int i = 0; i < message.length(); i++) {
            if (message.charAt(i)=='|'){
                aux = 1;
            }
            // Ler fragmento
            else if(aux == 0){
                fragment += message.charAt(i);
            }
            // Ler payload
            else {
                payload += message.charAt(i);
            }
        }

        this.defragmentMessages += payload;

        if (Integer.parseInt(fragment) == 0){
            //System.out.println("\n\n DEFRAGMENTED MESSAGE:  " +this.defragmentMessages +"\n\n");  // COLOCAR ATIVO PARA DEMONSTRAR
            getBlocksFromNodes(this.defragmentMessages);
            this.defragmentMessages = "";
        }
    }


    // Função responsável por receber payload que indica que blocos são necessários de ir buscar a para um determinado Node
    public void getBlocksFromNodes (String payload){
        Map<Integer, List<String>> blocksToRetreive = new HashMap<>();
        String blockNum = "";
        String ipAdress = "";
        List<String> allIps = new ArrayList<>();
        int aux = 0;
        for (int i=0; i<payload.length(); i++){

            // Sabemos o bloco
            if(payload.charAt(i) == ':'){
                aux = 1;
            }
            // Bloco termina
            else if(payload.charAt(i) == ';'){
                allIps.add(ipAdress);
                aux = 0;
                if (!blocksToRetreive.containsKey(Integer.parseInt(blockNum))) {
                    blocksToRetreive.put(Integer.parseInt(blockNum), new ArrayList<>());
                }
                blocksToRetreive.get(Integer.parseInt(blockNum)).addAll(allIps);
                blockNum = "";
                ipAdress = "";
                allIps.clear();
            }
            // Novo ip
            else if(payload.charAt(i) == ','){
                allIps.add(ipAdress);
                ipAdress = "";
            }
            // Determinar o número do bloco
            else if(aux == 0){
                blockNum += payload.charAt(i);
            }
            // Determinar o número do ip
            else{
                ipAdress += payload.charAt(i);
            }
        }
        for (Map.Entry<Integer, List<String>> entry : blocksToRetreive.entrySet()) {
            Integer key = entry.getKey();
            List<String> values = entry.getValue();

            System.out.println("Key: " + key);  // COLOCAR ATIVO PARA DEMONSTRAR
            System.out.println("Values: " + values);  // COLOCAR ATIVO PARA DEMONSTRAR
        }
        sendToNodes(blocksToRetreive);
    }


    // Responsável por fechar comunicações entre o Node e outros Nodes e FS_Tracker
    public void disconnectNode(){
        killNode=true;
        try{
            if (bufferedToTracker != null) {
                bufferedToTracker.close();
            }
            if (bufferedFromTracker != null) {
                bufferedFromTracker.close();
            }
            if (socketTCP != null){
                socketTCP.close();  
            }
            if (socketUDP != null){
                socketUDP.close();  
            }
            if (timer != null) {
                timer.cancel();
                timer.purge();
            }

        } catch (IOException a){
            System.out.println("ERROR CLOSING NODE");
        }
        finally{
            System.out.println("Disconnected Sucessfully");   
            System.exit(0);
        }
        
    }

































    ////////////////////////////////////////////////////
    ////////////////////////////////////////////////////
    //COMUNICAÇÃO UDP
    ////////////////////////////////////////////////////
    ////////////////////////////////////////////////////





    // Algoritmo que determina que blocos são necessários ir buscar a cada Node
    public void sendToNodes (Map<Integer, List<String>> blocksToRetreive){
        Map<String, String> messages = new HashMap<>();
        for (Map.Entry<Integer, List<String>> entry : blocksToRetreive.entrySet()) {
            String value = entry.getKey().toString(); // número de bloco do ficheiro
            String key;
            key = entry.getValue().get(0); // primeiro ip que contém o bloco
            List<String> allValues = entry.getValue(); // todos os ips que contém o bloco

            if (!allValues.contains(ipNode)){
                if (!messages.containsKey(key)) {
                    messages.put(key, value);
                }
                else{
                    String aux = messages.get(key)+","+value;
                    messages.put(key, aux);
                }
            }
        
        }

        for (Map.Entry<String, String> entry : messages.entrySet()) {

            new Thread(() -> {
                String key = entry.getKey();
                String values = entry.getValue();
                try{
                    sendMessageToNode("0|"+ipNode+"|"+fileName+"|"+values, key);
                }
                catch (Exception e){
                    e.getMessage();
                }


                while (true) {

                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    l.lock();
                    try{
                        if(!hasStarted.contains(key)){
                            try {
                                sendMessageToNode("0|"+ipNode+"|"+fileName+"|"+values, key);
                                System.out.println("Resending Asking For file");
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        else{
                            break;
                        }
                    }
                    finally{
                        l.unlock();
                    }
                    
                }
                l.lock();
                try{
                    hasStarted.remove(key);    
                } 
                finally{
                    l.unlock();
                }
                
            }).start();
            
        }
    }



    // Recebe um payload e determina que blococos são necessários
    public void separateEachFile (String responseFromNode){
        String[] subst = responseFromNode.split("\\|");
        String ipDestino = subst[1];
        String filename = subst[2];
        String payload = subst[3];
        String[] substrings = payload.split("\\,");
        List<Integer> blocos = new ArrayList<>();
        for(String info: substrings){
            blocos.add(Integer.parseInt(info));
        }
        sendFiles(filename,ipDestino, blocos);
    }



    // Multithread para funcionar em paralelo
    public void sendFiles(String filename, String ipToSend, List<Integer> blocos){
        new Thread(() -> {
            String fileBlockName=ipToSend+blocos.get(0);
            int lastElement = blocos.get(blocos.size()-1);
            int lengthFragments = 0;
            l.lock();
            try{
                fragmentoAtual.remove(fileBlockName);
                for(int frag : blocos){
                    lengthFragments+=allNodeFiles.get(filename).get(frag).length;
                }
                try {
                    sendMessageToNode("F|"+blocos.get(0)+"|"+lengthFragments+"|"+this.ipNode, ipToSend);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            finally{
                l.unlock();
            }

            while (true) {

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                l.lock();
                try{
                    if(!fragmentoAtual.containsKey(fileBlockName)){
                        try {
                            sendMessageToNode("F|"+blocos.get(0)+"|"+lengthFragments+"|"+this.ipNode, ipToSend);
                            System.out.println("Resending ..... F "); // NEEDED FOR DEBBUG
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    else{
                        break;
                    }
                }
                finally{
                    l.unlock();
                }

            }
            
            byte[] ipAddressBytes=null;
            try {
                ipAddressBytes = InetAddress.getByName(ipToSend).getAddress();
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
            boolean isLastFragment = false;
            int i = 0;
            for (Integer blocknum : blocos){
                i++;
                int keepCheck=0;

                // Mensagem com fragmentação (É a última)
                if (blocknum==lastElement) {
                    isLastFragment = true;
                }
                byte[] eachMessage = new byte[1024];
                if (isLastFragment){
                    eachMessage[0] = (byte) (1);
                }
                else{
                    eachMessage[0] = (byte) (0);
                }
                eachMessage[1] = (byte) (i & 0xFF);
                eachMessage[2] = (byte) ((i >> 8) & 0xFF);
                eachMessage[3] = (byte) (blocos.get(0) & 0xFF);
                eachMessage[4] = (byte) ((blocos.get(0) >> 8) & 0xFF);
                eachMessage[5] = (byte) (blocknum & 0xFF);
                eachMessage[6] = (byte) ((blocknum >> 8) & 0xFF);
                byte[] file = allNodeFiles.get("test2").get(blocknum);
                System.arraycopy(file, 0, eachMessage, 7, file.length);
                try {
                    sendMessageToNodeInBytes(eachMessage,ipToSend);
                    //System.out.println("ACK -> "+ i); // NEEDED FOR DEBBUG
                } catch (IOException e) {
                    e.printStackTrace();
                }

                int fragNow;
                while (true) {

                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }


                    l.lock();                      
                    try{
                        fragNow = fragmentoAtual.get(fileBlockName);
                    }
                    finally{
                        l.unlock();
                    }


                    if(i+1!=fragNow){
                        if(keepCheck==150) return;

                        keepCheck++;
                        try {
                            sendMessageToNodeInBytes(eachMessage,ipToSend);
                            System.out.println("Resending ..... ACK -> "+ i);  // NEEDED FOR DEBBUG
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    else{
                        break;
                    }
                }
            }
        }).start();
    }




    public void getFile(byte[] messageFragment){
        int last_fragment = (messageFragment[0]);
        int numero_sequencia = ((messageFragment[1] & 0xFF) | ((messageFragment[2] << 8) & 0xFF00));
        int firstBlock = ((messageFragment[3] & 0xFF) | ((messageFragment[4] << 8) & 0xFF00));
        int blocknumber = ((messageFragment[5] & 0xFF) | ((messageFragment[6] << 8) & 0xFF00));
        String nameFile=ipNode+firstBlock;
        int n_seq_esperado;
        l.lock();
        try{
            n_seq_esperado = n_sequencia_esperado.get(nameFile);
        }
        finally{
            l.unlock();
        }


        // Se o número de sequencia for o esperado, tudo corre bem
        if (numero_sequencia == n_seq_esperado){

            try{
                if (last_fragment == 1){

                    int size;
                    l.lock();
                    try{
                        size = (totalSize.get(nameFile))%(1017);
                        totalSize.remove(nameFile);
                        filesDownloaded.add(nameFile);
                    }
                    finally{
                        l.unlock();
                    }
                    

                    byte[] file_info = new byte[size];
                    System.arraycopy(messageFragment, 7, file_info, 0, size);

                    l.lock();
                    try{
                        outputBlocks.put(blocknumber, file_info);
                        sendMessageToNode("ACK"+n_seq_esperado+"|"+nameFile, ipToSendAKCS.get(nameFile));
                        System.out.println("Contents of outputBlocks:");
        for (Map.Entry<Integer, byte[]> entry : outputBlocks.entrySet()) {
            int key = entry.getKey();
            byte[] value = entry.getValue();
            System.out.println("Key: " + key + ", Value Length: " + value.length);
            // You can print the actual content of the byte array if needed
            // System.out.println("Value: " + Arrays.toString(value));
        }
                    }
                    finally{
                        l.unlock();
                    }
                    
                }
                else{
                    byte[] file_info = new byte[1017];
                    System.arraycopy(messageFragment, 7, file_info, 0, file_info.length);

                    l.lock();
                    try{
                        outputBlocks.put(blocknumber, file_info);
                        sendMessageToNode("ACK"+n_seq_esperado+"|"+nameFile, ipToSendAKCS.get(nameFile));
                    }
                    finally{
                        l.unlock();
                    }

                }

                l.lock();
                try{
                    n_sequencia_esperado.put(nameFile, n_seq_esperado+1);    
                }
                finally{
                    l.unlock();
                }
                
            }
            catch (IOException e){
                e.printStackTrace();
            }

        }
        else{
            l.lock();
            try{
                try{
                    sendMessageToNode("ACK"+(n_seq_esperado-1)+"|"+nameFile, ipToSendAKCS.get(nameFile));
                } catch (IOException e){ }
            }
            finally{
                l.unlock();
            }

        }

    }

    



    public void listenMessageFromNode() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (socketTCP.isConnected() && !killNode) {


                    //if(hasDownloadStarted && outputStream.isEmpty()){
                    //    hasDownloadStarted = false;
                    //    System.out.println("Download completed!");
                    //    if (!filesDownloaded.isEmpty()){
                    //        String fileName = (filesDownloaded.get(0)).substring(0, (filesDownloaded.get(0)).length()-8);
                    //        String payload = fileName + ":";
                    //        for (String fName: filesDownloaded){
                    //            char unitChar = fName.charAt(fName.length()-1);
                    //            char decimalChar = fName.charAt(fName.length()-2);
                    //            int blockNumber = (decimalChar - 'a') * 26 + (unitChar - 'a') + 1;

                    //            payload+=blockNumber+",";
                    //        }
                    //        payload = payload.substring(0, payload.length() - 1) + ";";
                    //        System.out.println(payload);
                    //        try {
                    //            sendInfoToFS_Tracker(payload);
                    //        } catch (IOException e) {
                    //            e.printStackTrace();
                    //        }
                    //    }

                        //try (FileOutputStream fos = new FileOutputStream(fileName)) {
                        //    File infoFile = new File("/home/core/Desktop/Projeto/"+ipNode);
                        //    File[] allFiles = infoFile.listFiles();
                        //    Arrays.sort(allFiles);
                        //    for (File file : allFiles){
                        //        if (filesDownloaded.contains(file.getName())){
                        //            
                        //            BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
                        //            // Read from the current file
                        //            byte[] buffer = new byte[4096];
                        //            int bytesRead;
                        //            while ((bytesRead = bis.read(buffer)) != -1) {
                        //                // write to the output file
                        //                fos.write(buffer, 0, bytesRead);
                        //            }
                        //        }
                        //    }
                        //
                        //}
                        //catch(IOException e){
                        //    e.printStackTrace();
                        //}
                    //    filesDownloaded.clear();
                    //}

                    try {
                        byte[] receiveData = new byte[1024];
                        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

                        socketUDP.receive(receivePacket);
                        String responsenFromNode = new String(receivePacket.getData(), 0, receivePacket.getLength());
                        // First message
                        if (responsenFromNode.startsWith("0|")){
                            System.out.println("Received: " + responsenFromNode); // NEEDED FOR DEBBUG
                            separateEachFile(responsenFromNode);
                        }
                        // Create the file
                        else if (responsenFromNode.startsWith("F|")){
                            String[] split = responsenFromNode.split("\\|");
                            String filename = ipNode+split[1];
                            System.out.println("Received FileName: " + filename); // NEEDED FOR DEBBUG
                            l.lock();
                            try{
                                n_sequencia_esperado.put(filename, 1);
                                hasStarted.add(split[3]);
                                totalSize.put(filename,  Integer.parseInt(split[2]));
                                ipToSendAKCS.put(filename, split[3]); // fileName -> ipOrigem 
                            }
                            finally{
                                l.unlock();
                            }
                            
                            sendMessageToNode("ACK0|"+filename, split[3]);
                        }
                        else if (responsenFromNode.startsWith("ACK")){
                            String[] split = responsenFromNode.split("\\|");
                            int ack_num = Integer.parseInt(split[0].substring(3));

                            System.out.println("ACK :"+ split[1]);
                            l.lock();
                            try{
                                fragmentoAtual.put(split[1], ack_num+1);
                            }
                            finally{
                                l.unlock();
                            }
                        }
                        // Fragmented messages
                        else{
                            hasDownloadStarted = true;
                            getFile(receivePacket.getData());
                        }
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    // IP a quem eu quero enviar uma String
    public void sendMessageToNode(String messageToSend, String ipToSend) throws IOException {
        l.lock();
        try{
            DatagramSocket clientSocket = new DatagramSocket();
            byte[] buf = messageToSend.getBytes();
            DatagramPacket p = new DatagramPacket(buf, buf.length, InetAddress.getByName(ipToSend), 9090);
            clientSocket.send(p);
            clientSocket.close();
            System.out.println("Sent: "+messageToSend); // NEEDED FOR DEBBUG
        }
        finally{
            l.unlock();
        }

    }



    // IP a quem eu quero enviar uma lista de bytes
    public void sendMessageToNodeInBytes(byte[] messageToSend, String ipToSend) throws IOException {
        l.lock();
        try{
            DatagramSocket clientSocket = new DatagramSocket();
            DatagramPacket p = new DatagramPacket(messageToSend, messageToSend.length, InetAddress.getByName(ipToSend), 9090);
            clientSocket.send(p);
            clientSocket.close();
        }
        finally{
            l.unlock();
        }

    }


































    public static void main (String[] args) throws IOException{
        Socket socketTCP = new Socket("10.4.4.1",9090); //"localhost"
        String ipNode = socketTCP.getLocalAddress().toString().substring(1);

        DatagramSocket socketUDP = new DatagramSocket(9090);

        String pathToFiles;
        if (ipNode.equals("10.1.1.1")){
            pathToFiles = "/home/core/Desktop/Projeto/Node1";
        }
        else if (ipNode.equals("10.2.2.2")){
            pathToFiles = "/home/core/Desktop/Projeto/Node2";
        }
        else if (ipNode.equals("10.2.2.1")){
            pathToFiles = "/home/core/Desktop/Projeto/Node3";
        }
        else{
            pathToFiles = "/home/core/Desktop/Projeto/Node4";
        }


        System.out.println("Conexão FS Track Protocol com servidor " + socketTCP.getInetAddress().getHostAddress() + " porta 9090.\n");
        Node node = new Node(ipNode, socketTCP, pathToFiles, socketUDP);
        node.listenMessageFromTracker();
        node.sendMessageToTracker();
        
        node.listenMessageFromNode();
    
        
    }

}





