package edu.sunysb.dbManager;


import java.io.BufferedWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeMap;

import com.mysql.jdbc.exceptions.MySQLTransactionRollbackException;
public class MBTreeUpdate {
	
	private final boolean UDBG = false;

	private  int METADATA =1;

	private  int ERROR_CODE = -3;
	
	int resultCount=-1;
	int boundaryKeyCount=-1;
	int boundaryHashCount=-1;
	int boundaryKeyLeftLeafId=-1;
	int boundaryKeyRightLeafId=-1;
	
	HashMap<Integer,ArrayList<Node>> levelWiseNodeList=new HashMap<Integer,ArrayList<Node>>();
	int branchingFactor;
	int height;
	//public String rootHash;
	StringBuffer newHashes;
	
	MBTreeUpdate(int branchingFactor, int height){
		this.branchingFactor=branchingFactor;
		this.height=height;
	}
	
	public static void main(String args[]){
		
		String searchQuery="select ";
		DBManager dbManager =new DBManager();
		Connection connection=dbManager.openConnection();
		//Connection connection=dbManager.getConnection();
		//MBTreeSearch mbTreeSearch=new MBTreeSearch();
		//mbTreeSearch.search(connection, searchQuery);
		dbManager.closeConnection();
		
	}
	

	public int update(BufferedWriter bw, int threadId, CallableStatement callableStatement, 
			int leftKey, int rightKey, String newVal, 
			Statement rootHashStmt, int runType) throws IOException{
		
		try {
			
			/* if it is simple Search call the procedure that does not collect hashes */
			if(runType==1){
				String simpleSearchString="call simpleSearch("+threadId+","+leftKey+","+rightKey+","+branchingFactor+","+height+")";
				//String searchString="call search("+leftKey+","+rightKey+","+MBTCreator.branchingFactor+","+MBTCreator.height+")";
				if(UDBG){
				System.out.println(simpleSearchString);
				}
				ResultSet rs=callableStatement.executeQuery(simpleSearchString);
				/*
				System.out.println(leftKey+" "+rightKey);
				while(rs.next()){
					String resultSet=rs.getString(1);
					System.out.println(resultSet);
				}*/
				return 0;
			}
			
			if(runType==3){
				levelWiseNodeList.clear();
				newHashes=new StringBuffer();
				String simpleSearchString="call simpleSearch("+threadId+","+leftKey+","+rightKey+","+branchingFactor+","+height+")";
				//String searchString="call search("+leftKey+","+rightKey+","+MBTCreator.branchingFactor+","+MBTCreator.height+")";
				if(UDBG){
				System.out.println(simpleSearchString);
				}
				ResultSet rs=callableStatement.executeQuery(simpleSearchString);
				String updateQuery="update btree set value1=CONCAT(key_id,':',"+"'"+newVal+"'"+") where key_id>="+leftKey+" and key_id<="+rightKey+" and level_id="+height;
				//System.out.println(updateQuery);
				int success=rootHashStmt.executeUpdate(updateQuery);
				return success;
			}
			
			levelWiseNodeList.clear();
			newHashes=new StringBuffer();
			String searchString="call search("+threadId+","+leftKey+","+rightKey+","+branchingFactor+","+height+")";
			//String searchString="call search("+leftKey+","+rightKey+","+MBTCreator.branchingFactor+","+MBTCreator.height+")";
			if(UDBG){
			System.out.println(searchString);
			}
			ResultSet rs=callableStatement.executeQuery(searchString);
			
			
			/* calculate the old root hash */
			ArrayList<Node> lastLevelNodeList=populateLastLevelNodeList(bw, rs);
			if(lastLevelNodeList==null){
				return -1;
			}
			levelWiseNodeList.put(height,lastLevelNodeList);
			populateBoundaryHashes(rs);
			String oldRootHash=processLevelWiseNodeList();
			
			/* only if runType is search with verification or update with verification, verify hashes */
			if(runType==2 || runType==4){
				/* verify the old root hash */
				ResultSet rootHashRs=rootHashStmt.executeQuery("select hash_val from mbtree where level_id=0 and leaf_id=0");
				while(rootHashRs.next()){
					String actualRootHash=rootHashRs.getString(1);
					if(!actualRootHash.equals(oldRootHash)){
						//System.out.println("Mismatching Root Hashes");;
					}
				}
			}
			
			/* only if runType is update with verification or without verification, then update the hashes */
			
			if(runType==3 || runType==4){
				/* reset the result set to point to the first row */
				rs.beforeFirst();
				levelWiseNodeList.clear();
				
				/* calculate the new root hash */
				lastLevelNodeList=populateLastLevelNodeListWithUpdate(bw, rs, newVal, leftKey, rightKey);
				if(lastLevelNodeList==null){
					return -1;
				}
				levelWiseNodeList.put(height,lastLevelNodeList);
				populateBoundaryHashes(rs);
				String newRootHash=processLevelWiseNodeList();
				
				/* update the old root hash with the new root hash */
				//MBTCreator.rootHash=newRootHash;
				if(UDBG){
				System.out.println("New hash:"+newRootHash);
				}
				/* call the update procedure to update everything in the DB */
				searchString="call btreeUpdate("+leftKey+","+rightKey+","+height+","+"'"+newVal+"'"+","+"'"+newHashes+"'"+")";
				rs=callableStatement.executeQuery(searchString);
			}
			
		}catch(MySQLTransactionRollbackException e){
			return ERROR_CODE;
		}
		catch (SQLException e) {
			//return ERROR_CODE;
			e.printStackTrace();
			//System.exit(0);
			
		}
		return 0;
	
	}
	
	private void populateBoundaryHashes(ResultSet rs){
		//send all the intermediate level hashes obtained from search to the respective list in the hashmap
		try {
			int count=0;
			while(count<boundaryHashCount){
				rs.next();
				rs.getString(1);
				String parts[]=rs.getString(1).split(",");
				int levelId=Integer.parseInt(parts[0]);
				int leafId=Integer.parseInt(parts[1]);
				String hashVal=parts[2];
				Node node=new Node(leafId,hashVal);
				if(levelWiseNodeList.containsKey(levelId)){
					ArrayList<Node> nodeList=levelWiseNodeList.get(levelId);
					nodeList.add(node);
					levelWiseNodeList.put(levelId, nodeList);
					
				}else{
					ArrayList<Node> nodeList=new ArrayList<Node>();
					nodeList.add(node);
					levelWiseNodeList.put(levelId, nodeList);
				}
				count++;
			}
			//sort all the lists in the hashmap
			Iterator<Integer> iter = levelWiseNodeList.keySet().iterator();
			while(iter.hasNext()){
				int levelId=iter.next();
				ArrayList<Node> nodeList = levelWiseNodeList.get(levelId);
				Collections.sort(nodeList);
				levelWiseNodeList.put(levelId, nodeList);
				
			}
		} catch (NumberFormatException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private String processLevelWiseNodeList() {
		for(int i=height;i>0;i--){
			Collections.sort(levelWiseNodeList.get(i));
			//printList(levelWiseNodeList.get(i));
			processLevelNodes(i,levelWiseNodeList.get(i));
		}
		return levelWiseNodeList.get(0).get(0).getHashVal();
	}
	
	

	private void processLevelNodes(int levelId, ArrayList<Node> nodeList) {
		//System.out.println("Processing level "+levelId);
		//System.out.println(levelId+" "+nodeList.size());
		//System.out.println("My parents");
		if(nodeList.size()!=0){
			//int startLeafId=nodeList.get(0).getLeafId();
			//System.out.println(nodeList.size());
			/*
			 * work around for off by 1 to left example search(6120001,6161691,25,4)
			 */
			int startId=0;
			//if(nodeList.get(0).leafId % MBTCreator.branchingFactor != 0)
			//	startId=1;
			//work around ends
			for(int i=startId;i<nodeList.size();i=i+branchingFactor){
				
				String hashVal="";
				int parentLeafId=nodeList.get(i).getLeafId()/branchingFactor;
				//System.out.println("i "+i);
				/*
				 * work around for off by 1 to right example search(6120002,6135000,25,4)
				 */
				//if(i+branchingFactor>nodeList.size())
					//continue;
				//workaround ends
				for(int j=i;j<i+branchingFactor;j++){
					
					//System.out.println("j "+j+" "+nodeList.get(j).leafId+" "+nodeList.get(j).hashVal);
					Node node=nodeList.get(j);
					hashVal=hashVal+node.getHashVal();
				}
				String unhashedVal=hashVal;
				hashVal=sha1(hashVal);
				Node node=new Node(parentLeafId,hashVal);
				
				if(levelWiseNodeList.containsKey(levelId-1)){
					ArrayList<Node> parentNodeList=levelWiseNodeList.get(levelId-1);
					int hasNodeWithLeaf=hasNodeWithLeafId(parentNodeList,parentLeafId);
					if(hasNodeWithLeaf!=-1){
						parentNodeList.remove(hasNodeWithLeaf);
					}
					parentNodeList.add(node);
					//if(levelId!=2)
						newHashes.append((levelId-1)+","+parentLeafId+','+hashVal+',');
					levelWiseNodeList.put(levelId-1, parentNodeList);
					
				}else{
					ArrayList<Node> parentNodeList=new ArrayList<Node>();
					parentNodeList.add(node);
					//if(levelId!=2)
						newHashes.append((levelId-1)+","+parentLeafId+','+hashVal+',');
					levelWiseNodeList.put(levelId-1, parentNodeList);
				}
				
			}
			
		}
		
	}

	private void printList(ArrayList<Node> nodeList){
		for(int i=0;i<nodeList.size();i++){
			if(UDBG){
			System.out.println(nodeList.get(i).getLeafId()+" "+nodeList.get(i).getHashVal());
			}
		}
	}
	
	private int hasNodeWithLeafId(ArrayList<Node> nodeList, int leafId){
		for(int i=0;i<nodeList.size();i++){
			if(nodeList.get(i).getLeafId()==leafId){
				return i;
			}
		}
		return -1;
	}
	
	
	private ArrayList<Node> populateLastLevelNodeList(BufferedWriter bw, ResultSet rs) throws IOException {
		// TODO Auto-generated method stub
		
		int rowCount;
		TreeMap<Integer,String> keyTreeMap = null;
		try {
			rowCount = getRowCount(rs);
			rowCount=rowCount-METADATA;
			
			if(rs.next()){
				String firstMetaRow=rs.getString(1);
				String parts[]=firstMetaRow.split(",");
				boundaryKeyCount=Integer.parseInt(parts[0]);
				boundaryHashCount=Integer.parseInt(parts[1]);
				boundaryKeyLeftLeafId=Integer.parseInt(parts[2]);
				boundaryKeyRightLeafId=Integer.parseInt(parts[3]);
				//System.out.println("boundaryKeyLeftLeafId="+boundaryKeyLeftLeafId);
				//System.out.println("boundaryKeyRightLeafId="+boundaryKeyRightLeafId);
				
			}
			
			if(boundaryKeyCount>=0 && boundaryHashCount>=0){
				resultCount=rowCount-(boundaryKeyCount+boundaryHashCount);
			}else{
				
				System.out.println("negative values in boundary key and boundary hash");
				return null;
			}
			//System.out.println("boundaryKeyCOunt="+boundaryKeyCount);
			//System.out.println("boundaryHashCOunt="+boundaryHashCount);
			//System.out.println("resultCount="+resultCount);
			//MBTCreator.bw.write("resultCount="+resultCount+"\n");
			keyTreeMap= new TreeMap<Integer,String>();
			//populating the leaf ids for results and boundary
			//rs.next();
			
			if(resultCount<=2){
				if(UDBG){
				System.out.println("Invalid Query");
				bw.write("Invalid Query\n");
				}
				
				
				return null;
			}
			if(resultCount>0){
				int count=0;
				while(count<resultCount&&rs.next()){
					//System.out.println(rs.getString(1));
					String parts[]=rs.getString(1).split(",");
					keyTreeMap.put(Integer.parseInt(parts[0]), sha1(parts[1]));
					count++;
				}
				count=0;
				while(count<boundaryKeyCount){
					if(rs.next()){
						//System.out.println(rs.getString(1));
						String parts[]=rs.getString(1).split(",");
						keyTreeMap.put(Integer.parseInt(parts[0]), parts[1]);
						count++;
					}
				}
				Iterator<Integer> iter=keyTreeMap.keySet().iterator();
				int boundaryLeafLeft=boundaryKeyLeftLeafId;
				ArrayList<Node> nodeList=new ArrayList<Node>();
				//System.out.println("\nLast level Leaf Hashes");
				while(iter.hasNext()){
					
					String hashVal="";
					for(int i=1;i<branchingFactor;i++){
						hashVal=hashVal+keyTreeMap.get(iter.next());
					}
					hashVal=sha1(hashVal);
					Node node=new Node(boundaryLeafLeft,hashVal);
					//System.out.println(boundaryLeafLeft+" "+hashVal);
					nodeList.add(node);
					boundaryLeafLeft++;
					
				}
				return nodeList;
				
			}
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
		
	}
	
	
	private ArrayList<Node> populateLastLevelNodeListWithUpdate(BufferedWriter bw, ResultSet rs, String newVal, int leftKey, int rightKey) throws IOException {

		// TODO Auto-generated method stub
		
		int rowCount;
		TreeMap<Integer,String> keyTreeMap = null;
		try {
			rowCount = getRowCount(rs);
			rowCount=rowCount-METADATA;
			
			if(rs.next()){
				String firstMetaRow=rs.getString(1);
				String parts[]=firstMetaRow.split(",");
				boundaryKeyCount=Integer.parseInt(parts[0]);
				boundaryHashCount=Integer.parseInt(parts[1]);
				boundaryKeyLeftLeafId=Integer.parseInt(parts[2]);
				boundaryKeyRightLeafId=Integer.parseInt(parts[3]);
				//System.out.println("boundaryKeyLeftLeafId="+boundaryKeyLeftLeafId);
				//System.out.println("boundaryKeyRightLeafId="+boundaryKeyRightLeafId);
			}
			
			if(boundaryKeyCount>=0 && boundaryHashCount>=0){
				resultCount=rowCount-(boundaryKeyCount+boundaryHashCount);
			}else{
				System.out.println("negative values in boundary key and boundary hash");
				return null;
			}
			//System.out.println("boundaryKeyCOunt="+boundaryKeyCount);
			//System.out.println("boundaryHashCOunt="+boundaryHashCount);
			//System.out.println("resultCount="+resultCount);
			//MBTCreator.bw.write("resultCount="+resultCount+"\n");
			keyTreeMap= new TreeMap<Integer,String>();
			//populating the leaf ids for results and boundary
			//rs.next();
			
			if(resultCount<=2){
				if(UDBG){
					System.out.println("Invalid Query");
					bw.write("Invalid Query\n");
					
				}
				
				return null;
			}
			if(resultCount>0){
				int count=0;
				while(count<resultCount&&rs.next()){
					//System.out.println(rs.getString(1));
					String parts[]=rs.getString(1).split(",");
					keyTreeMap.put(Integer.parseInt(parts[0]), sha1(parts[1]));
					count++;
				}
				count=0;
				while(count<boundaryKeyCount){
					if(rs.next()){
						//System.out.println(rs.getString(1));
						String parts[]=rs.getString(1).split(",");
						keyTreeMap.put(Integer.parseInt(parts[0]), parts[1]);
						count++;
					}
				}
				Iterator<Integer> iter=keyTreeMap.keySet().iterator();
				int boundaryLeafLeft=boundaryKeyLeftLeafId;
				ArrayList<Node> nodeList=new ArrayList<Node>();
				//System.out.println("\nLast level Leaf Hashes");
				while(iter.hasNext()){
					//System.out.println(iter.next()+" "+keyTreeMap.get(iter.next()));
					
					String hashVal="";
					for(int i=1;i<branchingFactor;i++){
						String val;
						int key=iter.next();
						if((key>=leftKey) && (key<=rightKey)){
							val=key+":"+newVal;
							val=sha1(val);
						}else{
							val=keyTreeMap.get(key);
						}
						hashVal=hashVal+val;
						//System.out.println(key+" "+val);
					}
					hashVal=sha1(hashVal);
					Node node=new Node(boundaryLeafLeft, hashVal);
					//System.out.println(boundaryLeafLeft+" "+hashVal);
					nodeList.add(node);
					newHashes.append(height+","+boundaryLeafLeft+','+hashVal+',');
					boundaryLeafLeft++;
					
				}
				return nodeList;
				
			}
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	
	}

	public static int getRowCount(ResultSet set) throws SQLException {  
	   int rowCount;  
	   int currentRow = set.getRow();            // Get current row  
	   rowCount = set.last() ? set.getRow() : 0; // Determine number of rows  
	   if (currentRow == 0)                      // If there was no current row  
	      set.beforeFirst();                     // We want next() to go to first row  
	   else                                      // If there WAS a current row  
	      set.absolute(currentRow);              // Restore it  
	   return rowCount;  
	}  
	
	public String sha1(String input) {
        MessageDigest mDigest;
        StringBuffer sb = new StringBuffer();
		try {
			mDigest = MessageDigest.getInstance("SHA1");
			byte[] result = mDigest.digest(input.getBytes());
	        for (int i = 0; i < result.length; i++) {
	            sb.append(Integer.toString((result[i] & 0xff) + 0x100, 16).substring(1));
	        }
		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
         
        return sb.toString();
    }
		
}

	
