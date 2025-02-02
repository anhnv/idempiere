/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
package org.compiere.model;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.exceptions.NegativeInventoryDisallowedException;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Msg;

/**
 * 	Project Issue Model
 *
 *	@author Jorg Janke
 *	@version $Id: MProjectIssue.java,v 1.2 2006/07/30 00:51:02 jjanke Exp $
 */
public class MProjectIssue extends X_C_ProjectIssue
{

	/**
	 * 
	 */
	private static final long serialVersionUID = 4714411434615096132L;

	/**
	 * 	Standard Constructor
	 *	@param ctx context
	 *	@param C_ProjectIssue_ID id
	 *	@param trxName transaction
	 */
	public MProjectIssue (Properties ctx, int C_ProjectIssue_ID, String trxName)
	{
		super (ctx, C_ProjectIssue_ID, trxName);
		if (C_ProjectIssue_ID == 0)
		{
			setMovementQty (Env.ZERO);
			setPosted (false);
			setProcessed (false);
		}
	}	//	MProjectIssue

	/**
	 * 	Load Constructor
	 * 	@param ctx context
	 * 	@param rs result set
	 *	@param trxName transaction
	 */
	public MProjectIssue (Properties ctx, ResultSet rs, String trxName)
	{
		super(ctx, rs, trxName);
	}	//	MProjectIssue

	/**
	 * 	New Parent Constructor
	 *	@param project parent
	 */
	public MProjectIssue (MProject project)
	{
		this (project.getCtx(), 0, project.get_TrxName());
		setClientOrg(project.getAD_Client_ID(), project.getAD_Org_ID());
		setC_Project_ID (project.getC_Project_ID());	//	Parent
		setLine (getNextLine());
		m_parent = project;
		//
		setMovementDate (new Timestamp(System.currentTimeMillis()));
		setMovementQty (Env.ZERO);
		setPosted (false);
		setProcessed (false);
	}	//	MProjectIssue

	/**	Parent				*/
	private MProject	m_parent = null;
	
	/**
	 *	Get the next Line No
	 * 	@return next line no
	 */
	private int getNextLine()
	{
		return DB.getSQLValue(get_TrxName(), 
			"SELECT COALESCE(MAX(Line),0)+10 FROM C_ProjectIssue WHERE C_Project_ID=?", getC_Project_ID());
	}	//	getLineFromProject

	/**
	 * 	Set Mandatory Values
	 *	@param M_Locator_ID locator
	 *	@param M_Product_ID product
	 *	@param MovementQty qty
	 */
	public void setMandatory (int M_Locator_ID, int M_Product_ID, BigDecimal MovementQty)
	{
		setM_Locator_ID (M_Locator_ID);
		setM_Product_ID (M_Product_ID);
		setMovementQty (MovementQty);
	}	//	setMandatory

	/**
	 * 	Get Parent
	 *	@return project
	 */
	public MProject getParent()
	{
		if (m_parent == null && getC_Project_ID() != 0)
			m_parent = new MProject (getCtx(), getC_Project_ID(), get_TrxName());
		return m_parent;
	}	//	getParent
	
	/**************************************************************************
	 * 	Process Issue
	 *	@return true if processed
	 */
	public boolean process()
	{
		if (!save())
			return false;
		if (getM_Product_ID() == 0)
		{
			log.log(Level.SEVERE, "No Product");
			return false;
		}

		MProduct product = MProduct.get (getCtx(), getM_Product_ID());

		//	If not a stocked Item nothing to do
		if (!product.isStocked())
		{
			setProcessed(true);
			return save();
		}

		/** @todo Transaction */

		//	**	Create Material Transactions **
		MTransaction mTrx = new MTransaction (getCtx(), getAD_Org_ID(), 
			MTransaction.MOVEMENTTYPE_WorkOrderPlus,
			getM_Locator_ID(), getM_Product_ID(), getM_AttributeSetInstance_ID(),
			getMovementQty().negate(), getMovementDate(), get_TrxName());
		mTrx.setC_ProjectIssue_ID(getC_ProjectIssue_ID());
		//
		MLocator loc = MLocator.get(getCtx(), getM_Locator_ID());
		
		Timestamp dateMPolicy = getMovementDate();
		
		if(getM_AttributeSetInstance_ID()>0){
			Timestamp t = MStorageOnHand.getDateMaterialPolicy(getM_Product_ID(), getM_AttributeSetInstance_ID(), get_TrxName());
			if (t != null)
				dateMPolicy = t;
		}
		
		boolean ok = true;
		try
		{
			if (getMovementQty().negate().signum() < 0)
			{
				String MMPolicy = product.getMMPolicy();
				Timestamp minGuaranteeDate = getMovementDate();
				int M_Warehouse_ID = getM_Locator_ID() > 0 ? getM_Locator().getM_Warehouse_ID() : getC_Project().getM_Warehouse_ID();
				MStorageOnHand[] storages = MStorageOnHand.getWarehouse(getCtx(), M_Warehouse_ID, getM_Product_ID(), getM_AttributeSetInstance_ID(),
						minGuaranteeDate, MClient.MMPOLICY_FiFo.equals(MMPolicy), true, getM_Locator_ID(), get_TrxName(), true);
				BigDecimal qtyToIssue = getMovementQty();
				for (MStorageOnHand storage: storages)
				{
					if (storage.getQtyOnHand().compareTo(qtyToIssue) >= 0)
					{
						storage.addQtyOnHand(qtyToIssue.negate());
						qtyToIssue = BigDecimal.ZERO;
					}
					else
					{
						qtyToIssue = qtyToIssue.subtract(storage.getQtyOnHand());
						storage.addQtyOnHand(storage.getQtyOnHand().negate());
					}

					if (qtyToIssue.signum() == 0)
						break;
				}
				if (qtyToIssue.signum() > 0)
				{
					ok = MStorageOnHand.add(getCtx(), loc.getM_Warehouse_ID(), getM_Locator_ID(), 
							getM_Product_ID(), getM_AttributeSetInstance_ID(),
							qtyToIssue.negate(),dateMPolicy, get_TrxName());
				}
			} 
			else 
			{
				ok = MStorageOnHand.add(getCtx(), loc.getM_Warehouse_ID(), getM_Locator_ID(), 
						getM_Product_ID(), getM_AttributeSetInstance_ID(),
						getMovementQty().negate(),dateMPolicy, get_TrxName());				
			}
		}
		catch (NegativeInventoryDisallowedException e)
		{
			log.severe(e.getMessage());
			StringBuilder error = new StringBuilder();
			error.append(Msg.getElement(getCtx(), "Line")).append(" ").append(getLine()).append(": ");
			error.append(e.getMessage()).append("\n");
			throw new AdempiereException(error.toString());
		}
		
		if (ok)
		{
			if (mTrx.save(get_TrxName()))
			{
				setProcessed (true);
				if (save())
					return true;
				else
					log.log(Level.SEVERE, "Issue not saved");		//	requires trx !!
			}
			else
				log.log(Level.SEVERE, "Transaction not saved");	//	requires trx !!
		}
		else
			log.log(Level.SEVERE, "Storage not updated");			//	OK
		//
		return false;
	}	//	process

}	//	MProjectIssue
